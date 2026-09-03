#!/usr/bin/env bash
# The acceptance of M46, asked of the container rather than of a process.
#
# The milestone claims two things at once, and they pull in opposite directions:
#
#   * `docker stop` is **indistinguishable from `docker kill` for the data**. Everything the server
#     acknowledged is there after a restart, either way. That is a claim about durability, and a
#     graceful stop is not allowed to be the only reason it holds;
#   * `docker stop` is **distinguishable for clients**. A request in flight when the signal lands
#     is served rather than cut, so the client sees an answer where `docker kill` gives it a
#     connection reset.
#
# Neither half is provable from the other, and neither is provable with a signal sent to a local
# JVM: `docker stop` sends `SIGTERM`, waits, and then sends `SIGKILL` on its own, and it is that
# whole sequence that ships with the image. `ci/restart.sh` covers the client half against a
# process; this covers both halves against the container, and it is a bench script rather than a
# tenth CI job - the property it checks moves once a year, and building the image costs minutes.
#
#   ./ci/stop-acceptance.sh
set -uo pipefail

readonly IMAGE=${BOCHKA_IMAGE:-bochka:acceptance}
readonly NAME=bochka-acceptance
readonly PORT=${BOCHKA_PORT:-19600}
readonly OBJECTS=${STOP_OBJECTS:-400}
readonly ROUNDS=${STOP_ROUNDS:-"stop kill"}

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
passed=0; failed=0

pass() { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail() { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }

cleanup() {
  local status=$?
  docker rm -f "$NAME" >/dev/null 2>&1
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
  if [ $((passed + failed)) -eq 0 ]; then
    echo "nothing was checked: that is a failure, not an empty result" >&2
    [ "$status" -eq 0 ] && status=1
  fi
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; there is no container to stop" >&2; exit 3; }

# The distribution first, and this is not boilerplate copied from the neighbours. The `Dockerfile`
# **copies** `bochka-app/build/install/bochka-app`; it does not build it. Without this line the
# image is made of whatever happens to be lying in the tree, and a control run that changed the
# shutdown window silently measured the binary from an earlier harness - reporting the property as
# holding when the code it was built from no longer had it.
echo "building the distribution and the image"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }
docker build -q -t "$IMAGE" "$root" >/dev/null || { echo "docker build failed" >&2; exit 3; }

cat > "$work/writer.py" <<'PY'
"""Fills the store, then leaves one upload in flight and waits to be interrupted.

Two phases, because the milestone claims two different things and one client cannot show both by
accident. The first phase records what the server acknowledged - that set, and only that set, has
to survive a restart. The second leaves a single slow `PUT` running and reports what happened to
it, which is the whole of the client-visible difference between the two stops: a request already
accepted is either served or cut.

Nothing is written after the signal. Counting the writes a client makes while the container is
legitimately down would put hundreds of honest refusals on both sides and hide the one answer that
distinguishes them.
"""
import hashlib
import http.client
import json
import os
import sys
import time
import urllib.parse

import boto3
from botocore.config import Config

ENDPOINT = os.environ["ENDPOINT"]
OBJECTS = int(os.environ["OBJECTS"])
OUT = os.environ["OUT"]
READY = os.environ["READY"]

client = boto3.client(
    "s3",
    endpoint_url=ENDPOINT,
    aws_access_key_id="stopkey",
    aws_secret_access_key="stopsecret",
    region_name="us-east-1",
    # No retries. A retry would paper over exactly the difference being measured: what the client
    # saw is the first answer it got, not the one it got after trying again.
    # `s3v4` said out loud, because the default for a presigned URL is the **v2** query form -
    # `AWSAccessKeyId`, `Signature`, `Expires` - and this server answers that with `403`. The
    # harness then reported the in-flight upload as cut, which is what a real defect would look
    # like too. Signing version is not a detail here; it is the difference between measuring the
    # server and measuring the client's default.
    config=Config(
        retries={"max_attempts": 0}, connect_timeout=5, read_timeout=30, signature_version="s3v4"
    ),
)
client.create_bucket(Bucket="stop")

acknowledged = {}
for n in range(OBJECTS):
    body = f"object-{n}-".encode() + bytes((n * 7 + i) % 251 for i in range(4096))
    client.put_object(Bucket="stop", Key=f"o/{n:05d}", Body=body)
    acknowledged[f"o/{n:05d}"] = hashlib.sha256(body).hexdigest()


# The in-flight upload is sent by hand over a presigned URL, and that is not fussiness.
#
# The first version handed boto3 a slow file-like body, and both stops came back as
# `EndpointConnectionError` - the same answer a broken server would give. The reason is that
# botocore signs the payload, which means it reads the **whole** body before putting a byte on the
# wire: the container was stopped while the client was still hashing locally, so nothing was ever
# in flight and the harness was measuring itself. A presigned URL is signed without the payload,
# so the bytes can be dribbled out at a chosen rate with the request already open.
url = client.generate_presigned_url(
    "put_object", Params={"Bucket": "stop", "Key": "inflight"}, ExpiresIn=300
)
parsed = urllib.parse.urlsplit(url)
payload = bytes((i * 13 + 5) % 251 for i in range(256 * 1024))
chunks = 24
digest = hashlib.sha256(payload * chunks).hexdigest()

connection = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=30)
connection.putrequest("PUT", parsed.path + ("?" + parsed.query if parsed.query else ""))
connection.putheader("Content-Length", str(len(payload) * chunks))
connection.endheaders()

# One chunk before the shell is told to signal: the request is genuinely on the wire and the
# server has read its head by the time anything is sent to the container.
connection.send(payload)
open(READY, "w").close()
began = time.monotonic()
try:
    for _ in range(chunks - 1):
        time.sleep(0.15)
        connection.send(payload)
    answer = connection.getresponse()
    body_back = answer.read()
    if answer.status == 200:
        inflight = "served"
        acknowledged["inflight"] = digest
    else:
        inflight = f"status-{answer.status}"
        print(f"the in-flight upload was answered {answer.status}: {body_back[:200]!r}", file=sys.stderr)
except Exception as cut:  # noqa: BLE001 - the kind of failure is the finding
    inflight = type(cut).__name__
    print(f"the in-flight upload failed: {cut}", file=sys.stderr)

json.dump({"acknowledged": acknowledged, "inflight": inflight}, open(OUT, "w"))
print(f"{len(acknowledged)} acknowledged, in-flight upload {inflight} after "
      f"{time.monotonic() - began:.1f}s", file=sys.stderr)
PY

cat > "$work/verify.py" <<'PY'
"""Reads back everything the writer was told had been stored, and says how much of it is right."""
import hashlib
import json
import os

import boto3

client = boto3.client(
    "s3",
    endpoint_url=os.environ["ENDPOINT"],
    aws_access_key_id="stopkey",
    aws_secret_access_key="stopsecret",
    region_name="us-east-1",
)
recorded = json.load(open(os.environ["RECORD"]))["acknowledged"]
missing = 0
different = 0
for key, digest in recorded.items():
    try:
        body = client.get_object(Bucket="stop", Key=key)["Body"].read()
    except Exception:  # noqa: BLE001
        missing += 1
        continue
    if hashlib.sha256(body).hexdigest() != digest:
        different += 1
print(len(recorded), missing, different)
PY

start_container() {
  docker rm -f "$NAME" >/dev/null 2>&1
  docker run -d --name "$NAME" -p "$PORT:9000" -v "$1:/var/lib/bochka" \
    -u "$(id -u):$(id -g)" -e BOCHKA_KEYS=stopkey:stopsecret -e BOCHKA_LOG=1 \
    "$IMAGE" >/dev/null 2>&1
  for _ in $(seq 1 60); do
    curl -s -o /dev/null "http://127.0.0.1:$PORT/" && return 0
    sleep 0.5
  done
  return 1
}

# One round: fill, leave an upload in flight, stop the container the way the argument says, bring
# it back on the same volume, and ask both questions of what is there.
round() {
  local how=$1 data="$work/$1-data" ready="$work/$1-ready"
  mkdir -p "$data"
  rm -f "$ready"
  start_container "$data"

  docker run --rm --network host -v "$work:/w" \
    -e ENDPOINT="http://127.0.0.1:$PORT" -e OBJECTS="$OBJECTS" \
    -e OUT="/w/$how.json" -e READY="/w/$how-ready" \
    python:3.12-slim sh -c "pip install -q boto3 && python /w/writer.py" >"$work/$how.out" 2>&1 &
  local writer=$!

  # Waits for the upload to be in flight rather than sleeping and hoping: a round whose signal
  # arrived before or after it would answer a question nobody asked.
  local deadline=$((SECONDS + 300))
  while [ ! -f "$ready" ] && [ $SECONDS -lt $deadline ] && kill -0 "$writer" 2>/dev/null; do
    sleep 0.2
  done
  if [ ! -f "$ready" ]; then
    wait "$writer"
    fail "$how: the writer never reached its in-flight upload"
    tail -5 "$work/$how.out" | sed 's/^/          /'
    return
  fi
  sleep 1

  case "$how" in
    stop) docker stop "$NAME" >/dev/null 2>&1 ;;
    kill) docker kill "$NAME" >/dev/null 2>&1 ;;
  esac
  wait "$writer"

  docker rm -f "$NAME" >/dev/null 2>&1
  start_container "$data"

  local counted
  counted=$(docker run --rm --network host -v "$work:/w" \
    -e ENDPOINT="http://127.0.0.1:$PORT" -e RECORD="/w/$how.json" \
    python:3.12-slim sh -c "pip install -q boto3 && python /w/verify.py" 2>/dev/null | tail -1)
  local inflight
  inflight=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['inflight'])" "$work/$how.json" 2>/dev/null)
  printf '%s %s %s\n' "$how" "$counted" "${inflight:-unknown}" >> "$work/rows.txt"
  # What the client saw and what the container said about it, kept in the output rather than in a
  # deleted temporary directory: the timing is what tells a graceful stop from a `SIGKILL` that
  # arrived ten seconds later, and it took three rewrites of this harness to learn that.
  printf '    client: %s\n' "$(tail -2 "$work/$how.out" | tr '\n' ' ')"
  printf '    container: %s\n' "$(docker logs "$NAME" 2>&1 | tail -2 | tr '\n' ' ')"
  docker rm -f "$NAME" >/dev/null 2>&1
}

: > "$work/rows.txt"
for how in $ROUNDS; do round "$how"; done

[ -s "$work/rows.txt" ] || { echo "neither round produced a row" >&2; exit 3; }

echo
while read -r how acknowledged missing different inflight; do
  printf '  %-5s acknowledged %-5s missing %-3s different %-3s in-flight upload %s\n' \
    "$how" "$acknowledged" "$missing" "$different" "$inflight"
  # The durability half, asked of each stop on its own: what was acknowledged is there, whole.
  if [ "$missing" -eq 0 ] && [ "$different" -eq 0 ]; then
    pass "$how: all $acknowledged acknowledged objects came back byte-identical after the restart"
  else
    fail "$how: $missing acknowledged objects are gone and $different came back different"
  fi
done < "$work/rows.txt"

# And the half that says the two stops are not the same thing to a client. The evidence stays in a
# file rather than a variable so that a failure can show its working.
stopped=$(awk '$1 == "stop" { print $NF }' "$work/rows.txt")
killed=$(awk '$1 == "kill" { print $NF }' "$work/rows.txt")
echo
if [ -z "$stopped" ] || [ -z "$killed" ]; then
  # A comparison needs both sides. Running one round used to leave the other variable empty, and
  # `[ "" != served ]` is true - so a half-run reported that the two stops differ.
  fail "only one round ran (stop=${stopped:-none}, kill=${killed:-none}); the two stops cannot be compared"
elif [ "$stopped" = served ] && [ "$killed" != served ]; then
  pass "the two stops differ for clients: docker stop served the upload in flight, docker kill gave the client $killed"
elif [ "$stopped" != served ]; then
  fail "docker stop cut the upload it had already accepted ($stopped); a graceful stop should serve it"
else
  fail "docker kill also served the upload; the two stops are indistinguishable, which is the claim failing"
fi

printf '\n%d passed, %d failed\n' "$passed" "$failed"
[ $failed -eq 0 ] || exit 1
