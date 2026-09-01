#!/usr/bin/env bash
# Runs bochka and points other people's *data* systems at it (M41).
#
# `ci/live-clients.sh` asks whether an S3 client can talk to this server. This asks a harder
# question: whether a table format built on top of S3 works, which is a different set of promises —
# conditional writes that decide who wins a commit, listings that a reader walks to find a snapshot,
# and ranged reads of somebody else's file layout. A client that round-trips an object says nothing
# about any of them.
#
#   ./ci/consumers.sh
#
# Consumers run as containers, so the only thing that has to be installed is Docker. A consumer
# whose image cannot be pulled is SKIPPED rather than passed, and a run in which nothing executed
# is a failure: a skipped check reads exactly like a passing one.
set -uo pipefail

readonly KEY=bochkaadmin
readonly SECRET=bochkasecret
readonly PORT=${BOCHKA_PORT:-19200}
readonly ENDPOINT="http://127.0.0.1:${PORT}"

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
log="$work/bochka.log"
passed=0; failed=0; skipped=0

pass()  { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail()  { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }
skip()  { printf '  SKIPPED %s (%s)\n' "$1" "$2"; skipped=$((skipped+1)); }

cleanup() {
  local status=$?
  [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
  # In the trap, where it cannot be stepped over by an edit above it: a run that reached no
  # consumer at all exits 0 on its own and reads as a pass.
  if [ $((passed + failed)) -eq 0 ]; then
    echo "no consumer ran: that is a failure, not an empty result" >&2
    [ "$status" -eq 0 ] && status=1
  fi
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; every consumer would be skipped" >&2; exit 3; }

echo "building the distribution"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_LOG=1 BOCHKA_DATA_DIR="$work/data" \
  "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!

for _ in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.2
done
if ! (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then
  echo "bochka did not come up; log follows" >&2; cat "$log" >&2; exit 3
fi
echo "bochka is up on $ENDPOINT"
echo

have_image() { docker image inspect "$1" >/dev/null 2>&1 || docker pull -q "$1" >/dev/null 2>&1; }

# --- delta-rs: a table format whose commits are conditional writes (M-270) ---------------------
#
# The commit protocol is the point. Every writer tries to `PUT _delta_log/<n>.json` with
# `If-None-Match: *`, so the loser is told `412` and has to read what won, rebase and try again.
# A store that answers `200` to both writes loses a commit silently — the table still opens, and
# one writer's rows are simply not there.
if have_image python:3.12-slim; then
  cat > "$work/delta.py" <<'PY'
import concurrent.futures as cf
import boto3
import pyarrow as pa
from deltalake import CommitProperties, DeltaTable, write_deltalake

OPTIONS = {
    "AWS_ENDPOINT_URL": "http://127.0.0.1:19200",
    "AWS_ACCESS_KEY_ID": "bochkaadmin",
    "AWS_SECRET_ACCESS_KEY": "bochkasecret",
    "AWS_REGION": "us-east-1",
    "AWS_ALLOW_HTTP": "true",
    # What makes a commit a conditional write rather than a plain overwrite. Without it delta-rs
    # wants a lock table it cannot have here, and with an unsafe rename instead the losing writer
    # would win too - which is the failure this test is about.
    "conditional_put": "etag",
}
URI = "s3://consumers/race"
BUCKET = "consumers"
WRITERS, EACH = 6, 3

# Raised well above the default of 15, because the default measures how patient delta-rs is under
# contention rather than whether this store serialises commits. Six writers on one table is more
# contention than a real workload has; the budget is what keeps the subject of the test the store.
RETRIES = CommitProperties(max_commit_retries=128)


def append(writer: int) -> None:
    for round_ in range(EACH):
        write_deltalake(
            URI,
            pa.table({"n": [writer * 100 + round_], "who": [f"w{writer}"]}),
            storage_options=OPTIONS,
            mode="append",
            commit_properties=RETRIES,
        )


# The bucket is nobody's job in a table format: delta-rs writes into one and never makes one.
boto3.client(
    "s3",
    endpoint_url=OPTIONS["AWS_ENDPOINT_URL"],
    aws_access_key_id=OPTIONS["AWS_ACCESS_KEY_ID"],
    aws_secret_access_key=OPTIONS["AWS_SECRET_ACCESS_KEY"],
    region_name=OPTIONS["AWS_REGION"],
).create_bucket(Bucket=BUCKET)

write_deltalake(URI, pa.table({"n": [0], "who": ["seed"]}), storage_options=OPTIONS, mode="overwrite")
with cf.ThreadPoolExecutor(WRITERS) as pool:
    list(pool.map(append, range(WRITERS)))

table = DeltaTable(URI, storage_options=OPTIONS).to_pyarrow_table()
rows = table.num_rows
writers = {w for w in table.column("who").to_pylist() if w != "seed"}
expected = 1 + WRITERS * EACH
assert rows == expected, f"the table holds {rows} rows and every commit that returned should make {expected}"
assert len(writers) == WRITERS, f"only {sorted(writers)} survived: a commit was lost rather than retried"
print(f"ok rows={rows} writers={len(writers)}")
PY
  # Into a file and grepped afterwards, never `| grep -q`: `grep -q` leaves on its first match,
  # the writer takes a SIGPIPE, and under `pipefail` the pipeline reports 141 — a refusal that
  # depends on how much output there was. The file also means a failure has something to show.
  docker run --rm --network host -v "$work:/w" python:3.12-slim \
    sh -c "pip install -q deltalake pyarrow boto3 && python /w/delta.py" >"$work/delta.out" 2>&1
  if grep -q '^ok ' "$work/delta.out"; then
    # The union alone is not evidence: writers that happened to take turns would produce it too,
    # and then this test would say nothing about the commit protocol it exists to exercise. The
    # server's own log is asked whether anybody was ever refused.
    conflicts=$(grep -ac '_delta_log.*-> 412' "$log")
    if [ "$conflicts" -gt 0 ]; then
      pass "delta-rs: $conflicts commits lost the race, were told 412, and the table is their union"
    else
      fail "delta-rs: the table is right but no writer was ever refused; this run exercised no conflict"
    fi
  else
    fail "delta-rs: concurrent appends"
    tail -12 "$work/delta.out" | sed 's/^/          /'
  fi
else
  skip "delta-rs" "image unavailable"
fi

echo
echo "passed $passed, failed $failed, skipped $skipped"
[ "$failed" -eq 0 ] || exit 1
