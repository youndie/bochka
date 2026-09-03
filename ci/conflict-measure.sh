#!/usr/bin/env bash
# M-298: what a commit costs when writers collide, measured rather than assumed.
#
# `ci/consumers.sh` asserts that concurrent commits are serialised - the losers are told `412`,
# they rebase, and the table is their union. It says nothing about the price: how many commits a
# second survive the contention, how long the unluckiest one waits, and whether a writer can be
# starved out while the others make progress. Those are the three questions here.
#
# Three things about the shape of this measurement, because each of them decides what the numbers
# mean:
#
#   * **the subject is the store, not delta-rs.** The retry budget is raised to 128 for the same
#     reason `ci/consumers.sh` raises it: the shipped 15 measures how patient the client is. What
#     is varied is the number of writers and nothing else;
#   * **the numbers are still delta-rs numbers.** A commit is a data file, a conditional `PUT` of
#     the next log entry, and on a `412` a re-read of what won. So "commits per second" is what a
#     table format gets out of this store, not what the store's conditional write can do on its
#     own. The `412` count beside each row is the part that belongs to us;
#   * **one writer is a variant.** Without it the three contended rows have nothing to be compared
#     against, and "six commits a second" would be a number with no scale attached;
#   * **the same protocol is measured a second time without delta-rs.** A conditional `PUT` of the
#     next log entry, from the same threads, with no parquet and no table to re-read. If the two
#     numbers were close, the cost would be ours; they are not, and that is the finding rather
#     than an aside;
#   * **seven runs, median, spread printed.** One run per variant is not a measurement, and this
#     box has produced 1.14 and 2.42 processor-seconds for the same variant back to back. The
#     first run of every variant is thrown away: it pays for connections, DNS and whatever the
#     client warms up.
#
# Not a gate and not part of `check`: it prints a table and exits zero unless the run itself is
# broken. Run it on the Linux box, never on a laptop filesystem:
#
#   ./ci/conflict-measure.sh
set -uo pipefail

# The knobs deliberately avoid the `BOCHKA_` prefix: the server refuses to start on an unknown
# setting in that namespace, so a harness variable named `BOCHKA_CONFLICT_REPEATS` takes the server
# down with a helpful message about a typo nobody made. That refusal is a good property and this is
# what living with it looks like.
readonly PORT=${BOCHKA_PORT:-19500}
readonly ENDPOINT="http://127.0.0.1:${PORT}"
readonly HOME_DIR=${CONFLICT_HOME:-$HOME/.bochka-conflict}
readonly REPEATS=${CONFLICT_REPEATS:-7}
readonly EACH=${CONFLICT_EACH:-4}
readonly WRITERS=${CONFLICT_WRITERS:-"1 2 4 8"}

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
log="$work/bochka.log"
rows="$work/rows.txt"

cleanup() {
  [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; there is nothing to measure with" >&2; exit 3; }

# The same rule the JVM measurement code enforces for itself: a barrier on tmpfs costs nothing, so
# a store measured there is measuring memory. Here it would also hide the cost of the log write
# that every commit ends with.
fstype=$(findmnt -no FSTYPE -T "$(dirname "$HOME_DIR")" 2>/dev/null)
if [ "$fstype" = "tmpfs" ] || [ "$fstype" = "ramfs" ]; then
  echo "$HOME_DIR is on $fstype: a store measured in memory measures memory" >&2
  exit 3
fi

echo "building the distribution"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

rm -rf "$HOME_DIR"
BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_LOG=1 BOCHKA_DATA_DIR="$HOME_DIR" \
  "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!
for _ in $(seq 1 100); do
  (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.2
done
if ! (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then
  echo "bochka did not come up; log follows" >&2; cat "$log" >&2; exit 3
fi
echo "bochka is up on $ENDPOINT, data in $HOME_DIR"

cat > "$work/conflict.py" <<'PY'
import concurrent.futures as cf
import os
import time

import boto3
import pyarrow as pa
from deltalake import CommitProperties, DeltaTable, write_deltalake

ENDPOINT = os.environ["ENDPOINT"]
EACH = int(os.environ["EACH"])
REPEATS = int(os.environ["REPEATS"])
WRITERS = [int(w) for w in os.environ["WRITERS"].split()]
BUCKET = "measure"

OPTIONS = {
    "AWS_ENDPOINT_URL": ENDPOINT,
    "AWS_ACCESS_KEY_ID": "bochkaadmin",
    "AWS_SECRET_ACCESS_KEY": "bochkasecret",
    "AWS_REGION": "us-east-1",
    "AWS_ALLOW_HTTP": "true",
    "conditional_put": "etag",
}
# The budget, raised so that the subject stays this store rather than the client's patience.
RETRIES = CommitProperties(max_commit_retries=128)

boto3.client(
    "s3",
    endpoint_url=ENDPOINT,
    aws_access_key_id="bochkaadmin",
    aws_secret_access_key="bochkasecret",
    region_name="us-east-1",
).create_bucket(Bucket=BUCKET)


def one_run(writers: int, tag: str) -> tuple[float, float, float]:
    uri = f"s3://{BUCKET}/{tag}"
    write_deltalake(uri, pa.table({"n": [0], "who": ["seed"]}), storage_options=OPTIONS, mode="overwrite")

    def append(writer: int) -> list[float]:
        taken = []
        for round_ in range(EACH):
            began = time.monotonic()
            write_deltalake(
                uri,
                pa.table({"n": [writer * 100 + round_], "who": [f"w{writer}"]}),
                storage_options=OPTIONS,
                mode="append",
                commit_properties=RETRIES,
            )
            taken.append(time.monotonic() - began)
        return taken

    began = time.monotonic()
    with cf.ThreadPoolExecutor(writers) as pool:
        per_writer = list(pool.map(append, range(writers)))
    wall = time.monotonic() - began

    # Every commit that returned has to be in the table, or the number below is the throughput of
    # losing data.
    table = DeltaTable(uri, storage_options=OPTIONS).to_pyarrow_table()
    expected = 1 + writers * EACH
    if table.num_rows != expected:
        raise AssertionError(f"{tag}: the table holds {table.num_rows} rows rather than {expected}")

    every = [t for writer in per_writer for t in writer]
    # The slowest writer's total, against the fastest: starvation is one writer waiting while the
    # others finish, and an average over commits cannot see it.
    totals = [sum(writer) for writer in per_writer]
    return wall, max(every), max(totals) / min(totals)


def plain_run(writers: int, tag: str) -> tuple[float, float, float]:
    """The same write with the condition taken off it.

    One axis, and it is the condition: same client, same body, same durability, keys that cannot
    collide. Whatever separates this row from `bare` is what `If-None-Match` costs; whatever they
    share is what a durable write costs in this store.
    """
    client = boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        aws_access_key_id="bochkaadmin",
        aws_secret_access_key="bochkasecret",
        region_name="us-east-1",
    )
    body = b"x" * 512

    def write(writer: int) -> list[float]:
        taken = []
        for entry in range(EACH):
            began = time.monotonic()
            client.put_object(Bucket=BUCKET, Key=f"{tag}/{writer}-{entry:08d}.json", Body=body)
            taken.append(time.monotonic() - began)
        return taken

    began = time.monotonic()
    with cf.ThreadPoolExecutor(writers) as pool:
        per_writer = list(pool.map(write, range(writers)))
    wall = time.monotonic() - began
    every = [t for writer in per_writer for t in writer]
    totals = [sum(writer) for writer in per_writer]
    return wall, max(every), max(totals) / min(totals)


def bare_run(writers: int, tag: str) -> tuple[float, float, float]:
    """The same race with the table format taken out of it.

    Every thread tries to claim the next entry with `If-None-Match: *` and, when it is told `412`,
    moves on to the next number. That is a commit protocol with the parquet write and the re-read
    of the winner removed, so it is an upper bound on what this store can do for a client that
    commits this way - not a second opinion about delta-rs.
    """
    client = boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        aws_access_key_id="bochkaadmin",
        aws_secret_access_key="bochkasecret",
        region_name="us-east-1",
    )
    body = b"x" * 512

    def claim(writer: int) -> list[float]:
        taken = []
        entry = 0
        for _ in range(EACH):
            began = time.monotonic()
            while True:
                try:
                    client.put_object(
                        Bucket=BUCKET, Key=f"{tag}/{entry:08d}.json", Body=body, IfNoneMatch="*"
                    )
                    entry += 1
                    break
                except client.exceptions.ClientError as refused:
                    if refused.response["ResponseMetadata"]["HTTPStatusCode"] != 412:
                        raise
                    entry += 1
            taken.append(time.monotonic() - began)
        return taken

    began = time.monotonic()
    with cf.ThreadPoolExecutor(writers) as pool:
        per_writer = list(pool.map(claim, range(writers)))
    wall = time.monotonic() - began
    every = [t for writer in per_writer for t in writer]
    totals = [sum(writer) for writer in per_writer]
    return wall, max(every), max(totals) / min(totals)


for writers in WRITERS:
    # Thrown away: the first run of a variant pays for connections and whatever the client warms up.
    one_run(writers, f"warmup-{writers}")
    for run in range(REPEATS):
        wall, worst, unfairness = one_run(writers, f"w{writers}-r{run}")
        commits = writers * EACH
        print(f"row delta {writers} {run} {commits / wall:.2f} {worst * 1000:.1f} {unfairness:.2f}", flush=True)

for writers in WRITERS:
    plain_run(writers, f"plainwarm-{writers}")
    for run in range(REPEATS):
        wall, worst, unfairness = plain_run(writers, f"p{writers}-r{run}")
        commits = writers * EACH
        print(f"row plain {writers} {run} {commits / wall:.2f} {worst * 1000:.1f} {unfairness:.2f}", flush=True)

for writers in WRITERS:
    bare_run(writers, f"barewarm-{writers}")
    for run in range(REPEATS):
        wall, worst, unfairness = bare_run(writers, f"b{writers}-r{run}")
        commits = writers * EACH
        print(f"row bare {writers} {run} {commits / wall:.2f} {worst * 1000:.1f} {unfairness:.2f}", flush=True)
print("done")
PY

docker run --rm --network host -v "$work:/w" \
  -e ENDPOINT="$ENDPOINT" -e EACH="$EACH" -e REPEATS="$REPEATS" -e WRITERS="$WRITERS" \
  python:3.12-slim sh -c "pip install -q deltalake pyarrow boto3 && python /w/conflict.py" \
  >"$work/conflict.out" 2>&1
if ! grep -q '^done' "$work/conflict.out"; then
  echo "the measurement did not finish; the tail of its output follows" >&2
  tail -20 "$work/conflict.out" >&2
  exit 3
fi

# The refusals belong to us rather than to the client, and they are counted per run because the
# prefix carries the run's name. A cumulative grep over the whole log would answer "how many ever".
: > "$rows"
while read -r _ kind writers run rate worst unfairness; do
  case "$kind" in
    delta) refused=$(grep -ac "w$writers-r$run/_delta_log.*-> 412" "$log") ;;
    bare)  refused=$(grep -ac "b$writers-r$run/.*-> 412" "$log") ;;
    *)     refused=$(grep -ac "p$writers-r$run/.*-> 412" "$log") ;;
  esac
  printf '%s %s %s %s %s %s %s\n' "$kind" "$writers" "$run" "$rate" "$worst" "$unfairness" "$refused" >> "$rows"
done < <(grep '^row ' "$work/conflict.out")

python3 - "$rows" "$EACH" "$REPEATS" <<'TABLE'
import statistics
import sys

rows = [line.split() for line in open(sys.argv[1]) if line.strip()]
each, repeats = sys.argv[2], sys.argv[3]
by_writers: dict[tuple[str, int], list[tuple[float, float, float, int]]] = {}
for kind, writers, _run, rate, worst, unfairness, refused in rows:
    by_writers.setdefault((kind, int(writers)), []).append(
        (float(rate), float(worst), float(unfairness), int(refused))
    )

print()
print(f"{repeats} runs per variant, {each} commits per writer, median with the range beside it")
print("plain = a durable PUT; bare = the same PUT made conditional; delta = a delta-rs commit")
print()
print(f"{'':>5} {'writers':>7}  {'commits/s':>19}  {'worst commit, ms':>26}  {'slowest/fastest':>15}  {'412s':>10}")
for kind, writers in sorted(by_writers, key=lambda k: (k[0], k[1])):
    taken = by_writers[(kind, writers)]
    rate = [t[0] for t in taken]
    worst = [t[1] for t in taken]
    unfairness = [t[2] for t in taken]
    refused = [t[3] for t in taken]
    print(
        f"{kind:>5} {writers:>7}  {statistics.median(rate):>8.2f} ({min(rate):.2f}-{max(rate):.2f})  "
        f"{statistics.median(worst):>10.0f} ({min(worst):.0f}-{max(worst):.0f})  "
        f"{statistics.median(unfairness):>15.2f}  {statistics.median(refused):>10.0f}"
    )
print()
TABLE
