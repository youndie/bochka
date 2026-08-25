"""A bounded, verifying load for a bochka that has to survive days rather than minutes.

Every other harness in this repository finishes in minutes on a directory nobody has used before.
That leaves four questions none of them can ask, because all four are about **time**:

  * does the index grow faster than the data it describes;
  * does compaction keep the journal flat without anybody calling it;
  * does resident memory drift under a limit that was measured once, under one traffic shape;
  * does the time to replay the journal grow with the age of the store.

So this writes for as long as it is left running, and it does two things that a load generator
usually does not.

**It is bounded by construction.** A fixed set of keys, each overwritten in place; the live set is
`KEYS * SIZE` and nothing else, whatever happens. A generator that appends until something breaks
measures the disk it was pointed at — and on a store whose volume is a directory on a shared node,
that disk belongs to somebody else too.

**It verifies what it reads.** Each object's bytes are derived from its key and the number of times
it has been written, so a reader knows what it should find. A soak that only writes proves the
process is alive, which nobody doubted; the question worth days of machine time is whether what
was written is still there and still itself.

Environment: BOCHKA_SOAK_ENDPOINT, BOCHKA_SOAK_KEY, BOCHKA_SOAK_SECRET, and optionally
BOCHKA_SOAK_KEYS / BOCHKA_SOAK_SIZE / BOCHKA_SOAK_REPORT_EVERY.
"""

import hashlib
import os
import random
import time

import boto3
from botocore.config import Config

ENDPOINT = os.environ["BOCHKA_SOAK_ENDPOINT"]
KEYS = int(os.environ.get("BOCHKA_SOAK_KEYS", "2000"))
SIZE = int(os.environ.get("BOCHKA_SOAK_SIZE", str(2 * 1024 * 1024)))
# A second, much smaller family, and it exists because of its size rather than despite it: S3
# refuses any part but the last under five mebibytes, so a multipart upload of the objects above
# cannot be made at all. Fifty of twelve mebibytes is six hundred more, which is affordable, and
# without them the completion path never runs.
BIG_KEYS = int(os.environ.get("BOCHKA_SOAK_BIG_KEYS", "50"))
BIG_SIZE = int(os.environ.get("BOCHKA_SOAK_BIG_SIZE", str(12 * 1024 * 1024)))
REPORT_EVERY = int(os.environ.get("BOCHKA_SOAK_REPORT_EVERY", "500"))
# A pause between cycles, and it is not throttling for its own sake: what this measures is age and
# accumulated writes, not throughput — that is what the benchmarks are for. Running flat out writes
# far more than any of the four questions needs and makes the generator a poor neighbour on a node
# it shares, which is the only kind of node a soak ever gets.
DELAY = float(os.environ.get("BOCHKA_SOAK_DELAY", "0.1"))
# How reads are spread over the keys, and this is the parameter that decides whether a run can say
# anything about caching at all.
#
# The first hundred hours ran uniform — every key equally likely — and that is the one shape under
# which a page cache never helps: with a working set many times the cache, every read misses
# whatever the cache size is, so squeezing it from 605 MiB to 144 changed nothing measurable.
# `active_file` stayed at 4 MiB against 600 of inactive, which is what "nothing is hot" looks like.
#
# Real traffic is skewed, so the default is too: a tenth of the keys take nine reads in ten. Set
# BOCHKA_SOAK_HOT_SHARE=0 to get the old uniform behaviour back — useful for measuring the disk,
# useless for measuring the cache.
HOT_FRACTION = float(os.environ.get("BOCHKA_SOAK_HOT_FRACTION", "0.1"))
HOT_SHARE = float(os.environ.get("BOCHKA_SOAK_HOT_SHARE", "0.9"))
BUCKET = "soak"
CONTROL = "control"
STOP_KEY = "stop"

s3 = boto3.client(
    "s3",
    endpoint_url=ENDPOINT,
    aws_access_key_id=os.environ["BOCHKA_SOAK_KEY"],
    aws_secret_access_key=os.environ["BOCHKA_SOAK_SECRET"],
    region_name="us-east-1",
    config=Config(retries={"max_attempts": 3}, s3={"addressing_style": "path"}),
)


def body(key: str, generation: int, size: int = SIZE) -> bytes:
    """The object's bytes, derived from its name and how many times it has been written.

    A repeating block rather than random data: the point is that a reader can recompute it, and
    the cost of producing two mebibytes has to stay well under the cost of storing them or the
    generator measures itself.
    """
    seed = hashlib.sha256(f"{key}:{generation}".encode()).digest()
    return (seed * (size // len(seed) + 1))[:size]


def stopped() -> bool:
    """Whether somebody has asked this to stop, without redeploying anything.

    A soak that can only be stopped by deleting its pod is a soak nobody stops early, and stopping
    early is exactly what you want when the node it shares starts running out of something.
    """
    try:
        s3.head_object(Bucket=CONTROL, Key=STOP_KEY)
        return True
    except Exception:
        return False


def main() -> None:
    for name in (BUCKET, CONTROL):
        try:
            s3.create_bucket(Bucket=name)
        except Exception:
            pass

    generations = [0] * KEYS
    big_generations = [0] * BIG_KEYS
    # The hot set is the first slice of the key space rather than a random sample, so that two runs
    # against two configurations read the **same** objects: a comparison whose arms disagree about
    # which keys are hot compares two workloads, not two configurations.
    hot = max(1, int(KEYS * HOT_FRACTION))
    latency = {"hot": [], "cold": []}
    counts = {"put": 0, "get": 0, "multipart": 0, "deleted": 0, "listed": 0, "mismatch": 0, "error": 0}
    started = time.monotonic()
    cycle = 0

    while True:
        if stopped():
            # Idle rather than exit: a generator that exits is restarted by its own Deployment and
            # exits again, which turns "pause this" into a crash loop. Sleeping means the same one
            # object both stops and resumes it, with nothing to redeploy either way.
            print("soak paused: the control object is present", flush=True)
            while stopped():
                time.sleep(30)
            print("soak resumed: the control object is gone", flush=True)

        cycle += 1
        index = random.randrange(KEYS)
        key = f"k{index:06d}"
        try:
            # A write, and every hundredth one goes through the multipart path instead: a store
            # that is only ever written one way is only ever tested one way.
            if cycle % 100 == 0:
                big = random.randrange(BIG_KEYS)
                big_key = f"m{big:04d}"
                big_generations[big] += 1
                payload = body(big_key, big_generations[big], BIG_SIZE)
                upload = s3.create_multipart_upload(Bucket=BUCKET, Key=big_key)["UploadId"]
                half = len(payload) // 2
                parts = []
                for number, chunk in ((1, payload[:half]), (2, payload[half:])):
                    tag = s3.upload_part(
                        Bucket=BUCKET, Key=big_key, UploadId=upload, PartNumber=number, Body=chunk
                    )["ETag"]
                    parts.append({"PartNumber": number, "ETag": tag})
                s3.complete_multipart_upload(
                    Bucket=BUCKET, Key=big_key, UploadId=upload, MultipartUpload={"Parts": parts}
                )
                counts["multipart"] += 1
                got = s3.get_object(Bucket=BUCKET, Key=big_key)["Body"].read()
                if got != payload:
                    counts["mismatch"] += 1
                    print(f"MISMATCH {big_key} at generation {big_generations[big]}", flush=True)
            else:
                generations[index] += 1
                s3.put_object(Bucket=BUCKET, Key=key, Body=body(key, generations[index]))
            counts["put"] += 1

            # And a read of some **other** key, so the check is about what survived rather than
            # about what was written a millisecond ago.
            warm = random.random() < HOT_SHARE
            other = random.randrange(hot) if warm else random.randrange(KEYS)
            if generations[other] > 0:
                name = f"k{other:06d}"
                started_read = time.monotonic()
                got = s3.get_object(Bucket=BUCKET, Key=name)["Body"].read()
                elapsed = (time.monotonic() - started_read) * 1000
                counts["get"] += 1
                latency["hot" if other < hot else "cold"].append(elapsed)
                if got != body(name, generations[other]):
                    counts["mismatch"] += 1
                    print(f"MISMATCH {name} at generation {generations[other]}", flush=True)

            # A key removed and written again, because a store that only grows never exercises the
            # path where an index entry goes away and its file has to follow.
            if cycle % 250 == 0:
                gone = random.randrange(KEYS)
                s3.delete_object(Bucket=BUCKET, Key=f"k{gone:06d}")
                generations[gone] = 0
                counts["deleted"] += 1

            if cycle % 50 == 0:
                s3.list_objects_v2(Bucket=BUCKET, MaxKeys=100)
                counts["listed"] += 1
        except Exception as e:  # noqa: BLE001 — a soak reports and continues; one failure is not the end
            counts["error"] += 1
            print(f"ERROR cycle {cycle}: {type(e).__name__}: {e}", flush=True)
            time.sleep(1)

        if DELAY:
            time.sleep(DELAY)

        if cycle % REPORT_EVERY == 0:
            hours = (time.monotonic() - started) / 3600
            # Reported per report rather than cumulatively, and emptied afterwards: what the
            # comparison needs is how reads behave **now**, and an average over a hundred hours
            # hides the hour where they changed.
            def spread(name: str) -> str:
                got = sorted(latency[name])
                if not got:
                    return f"{name}=-"
                return f"{name}={got[len(got) // 2]:.1f}/{got[int(len(got) * 0.9)]:.1f}ms"

            print(
                f"soak {hours:.2f}h cycle {cycle} "
                + " ".join(f"{k}={v}" for k, v in counts.items())
                + f" read {spread('hot')} {spread('cold')}",
                flush=True,
            )
            latency["hot"].clear()
            latency["cold"].clear()


if __name__ == "__main__":
    main()
