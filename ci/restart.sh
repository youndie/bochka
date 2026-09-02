#!/usr/bin/env bash
# Restarts bochka underneath a client that is in the middle of a sync.
#
# Every harness in this repository so far starts a server, asks it something, and stops it. None of
# them asks the question an operator actually has: what happens to the upload that was running when
# the server went down. The claim being checked is not "the data survives" - `SIGKILL` tests cover
# that inside the JVM - but "the client finishes on its own", with its own default retries and
# nobody typing anything.
#
# Two rounds, because the milestone says the two stops differ for clients and not for data:
#   TERM - the stop an orchestrator sends, where the server serves what it has already accepted;
#   KILL - the stop that comes when the grace period runs out, mid-write, mid-response.
# Both have to end with every file present and byte-identical.
#
# The run is a failure when nothing executed: an rclone image that cannot be pulled reads exactly
# like a passing check, which is the whole reason this file is not a comment in another script.
set -uo pipefail

readonly KEY=bochkaadmin
readonly SECRET=bochkasecret
readonly PORT=${BOCHKA_PORT:-19100}
readonly ENDPOINT="http://127.0.0.1:${PORT}"
readonly FILES=${RESTART_FILES:-240}

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
data="$work/data"
log="$work/bochka.log"
passed=0; failed=0; skipped=0

pass()  { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail()  { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }
skip()  { printf '  SKIPPED %s (%s)\n' "$1" "$2"; skipped=$((skipped+1)); }

cleanup() {
  [ -n "${server_pid:-}" ] && kill -9 "$server_pid" 2>/dev/null
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null || docker run --rm -v "$work:/w" --entrypoint "" alpine:latest rm -rf /w/. 2>/dev/null
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; the client would be skipped" >&2; exit 3; }

start_server() {
  BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_DATA_DIR="$data" \
    "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >>"$log" 2>&1 &
  server_pid=$!
  for _ in $(seq 1 100); do
    (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && return 0
    sleep 0.2
  done
  return 1
}

rc() {
  docker run --rm --network host -v "$work:/work" --entrypoint "" rclone/rclone:latest rclone \
    --s3-provider Other --s3-endpoint "$ENDPOINT" \
    --s3-access-key-id $KEY --s3-secret-access-key $SECRET --s3-region us-east-1 \
    --config /dev/null "$@"
}

echo "building the distribution"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

if ! docker image inspect rclone/rclone:latest >/dev/null 2>&1 && ! docker pull -q rclone/rclone:latest >/dev/null 2>&1; then
  skip "restart under rclone" "rclone image unavailable"
  echo "nothing ran, and a run in which nothing ran is a failure" >&2
  exit 3
fi

start_server || { echo "bochka did not come up; log follows" >&2; cat "$log" >&2; exit 3; }
echo "bochka is up on $ENDPOINT, data in $data"

# Many small files rather than one big one: the question is about a sync that is a sequence of
# requests, so the restart has to land between two of them rather than inside a single body.
tree="$work/tree"
mkdir -p "$tree"
for n in $(seq 1 $FILES); do
  head -c $((4096 + n)) /dev/urandom > "$tree/file-$n.bin"
done

# `sync` and not `copy`, because sync is what a person runs on a schedule, and it is the one that
# also lists the destination - a restart lands in the listing as often as in a body.
run_round() {
  local signal=$1 bucket=$2
  rc mkdir ":s3:$bucket" >/dev/null 2>&1

  # Defaults on purpose. Raising `--retries` would prove that rclone can be told to wait through a
  # restart; the claim is that it does so as shipped.
  rc sync /work/tree ":s3:$bucket" --transfers 4 >"$work/$bucket.sync" 2>&1 &
  local sync_pid=$!

  # The restart has to land inside the sync, and "sleep and hope" would let a fast machine finish
  # first and report a green tick for a restart that happened after the work. So: wait until the
  # bucket has something and the client is still running, and refuse the round otherwise.
  #
  # A quarter of the way in rather than "as soon as something is there": the first version waited
  # for four objects, and on a fast machine the client was already finishing when the signal
  # arrived - the round then proved that a restart after the work does no harm, which is not the
  # question. A quarter leaves three quarters of the files still to upload.
  local at_restart=0
  local enough=$((FILES / 4))
  for _ in $(seq 1 600); do
    at_restart=$(rc lsf ":s3:$bucket" 2>/dev/null | grep -c . )
    [ "$at_restart" -ge "$enough" ] && break
    kill -0 "$sync_pid" 2>/dev/null || break
    sleep 0.1
  done
  if ! kill -0 "$sync_pid" 2>/dev/null; then
    wait "$sync_pid"
    fail "$signal: the sync finished before the restart could land in it"
    return
  fi
  if [ "$at_restart" -lt 1 ] || [ "$at_restart" -ge "$FILES" ]; then
    kill -9 "$sync_pid" 2>/dev/null; wait "$sync_pid" 2>/dev/null
    fail "$signal: the restart would not have landed inside the sync ($at_restart of $FILES were there)"
    return
  fi

  kill -"$signal" "$server_pid" 2>/dev/null
  local gone=false
  for _ in $(seq 1 100); do
    kill -0 "$server_pid" 2>/dev/null || { gone=true; break; }
    sleep 0.1
  done
  $gone || { fail "$signal: the server did not stop"; return; }

  # Asked after the stop and not before it: between the listing above and the signal the client
  # can finish, and a round where it did finish says nothing about a restart it never saw.
  if ! kill -0 "$sync_pid" 2>/dev/null; then
    wait "$sync_pid"
    fail "$signal: the sync was over before the server went down"
    return
  fi

  start_server || { fail "$signal: the server did not come back"; return; }

  wait "$sync_pid"
  local sync_rc=$?
  if [ $sync_rc -ne 0 ]; then
    fail "$signal: rclone gave up on its own retries (exit $sync_rc)"
    sed -n '$p' "$work/$bucket.sync"
    return
  fi

  local after
  after=$(rc lsf ":s3:$bucket" 2>/dev/null | grep -c . )
  if [ "$after" -eq 0 ]; then
    fail "$signal: the bucket could not be listed after the round - the server is not answering"
    return
  fi
  if [ "$after" -le "$at_restart" ]; then
    fail "$signal: nothing was written after the restart ($at_restart then, $after now)"
    return
  fi

  # Not a count: `check` compares sizes and hashes file by file, which is what "no broken objects"
  # means. A restart that left a truncated body would leave the count right and the bytes wrong.
  if rc check /work/tree ":s3:$bucket" --one-way >"$work/$bucket.check" 2>&1; then
    pass "$signal: the sync finished by itself, $after objects, every one identical"
  else
    fail "$signal: objects differ after the restart"
    tail -n 5 "$work/$bucket.check"
  fi
}

run_round TERM restart-term
run_round KILL restart-kill

# The startup line from M-293, read back: the second boot has to have read the records the first
# one wrote. A restart that came up on an empty index would pass everything above by re-uploading
# the lot, and the sync would never notice.
starts=$(grep -c 'started in ' "$log")
replayed=$(grep 'started in ' "$log" | sed -n '2p')
if [ "$starts" -lt 3 ]; then
  fail "the server started $starts times, so at least one restart did not happen"
elif printf '%s' "$replayed" | grep -E 'reading (0|) index records' >/dev/null; then
  fail "the second start read no index records: $replayed"
else
  pass "the restarted server replayed its index: $replayed"
fi

printf '\n%d passed, %d failed, %d skipped\n' "$passed" "$failed" "$skipped"
[ $((passed + failed)) -gt 0 ] || { echo "nothing ran" >&2; exit 3; }
[ $failed -eq 0 ] || exit 1
