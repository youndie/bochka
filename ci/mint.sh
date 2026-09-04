#!/usr/bin/env bash
# MinIO's `mint` counted the way the other foreign suite is counted (M-285).
#
# Half a dozen real SDKs — Go, Java, Python, JavaScript, .NET and the command line — which is more
# independent implementations of the signature than the four live clients put together. That is the
# whole reason to run it: everything else in this repository signs the way somebody here understood
# the specification.
#
#   ./ci/mint.sh            # every SDK
#   ./ci/mint.sh minio-py   # one of them
#
# **The image is pinned by digest.** `mint` is MinIO's, its community edition has been wound down,
# and a suite that can disappear is a number that can stop being reproducible. The tag would also
# move under us silently, which is the failure this whole file exists to avoid.
#
# **What it is not.** `mint` is not an S3 conformance suite: it tests MinIO's own extensions too,
# and a failure of one of those says nothing about S3. Those are named in `ci/mint-scope.txt` with
# a reason each, the same discipline `ci/s3-tests.sh` applies to `ceph/s3-tests`.
set -uo pipefail

# The digest, not the tag. Read `mint` afresh with `docker pull minio/mint:latest` and put the new
# one here on purpose, never by accident.
readonly IMAGE=${BOCHKA_MINT_IMAGE:-minio/mint@sha256:f80ec8f981a5b54d98c1bbbbe7a191e5a2c696c5f45f4d18f712c418cec6e61d}
readonly PORT=${BOCHKA_PORT:-19400}
readonly KEY=bochkaadmin
readonly SECRET=bochkasecret

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
log="$work/bochka.log"
ran=no

cleanup() {
  local status=$?
  [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
  chmod -R u+w "$work" 2>/dev/null
  # Kept, the way `ci/s3-tests.sh` keeps its own: an unclassified failure is a question about one
  # case, and without the log the only way to ask it is another fifteen-minute run. Measured --
  # that is what the first classification of `test_presigned_post_policy_error` cost.
  if [ -f "$work/log/log.json" ] || [ -f "$log" ]; then
    mkdir -p "$root/build/mint"
    cp -f "$work/log/log.json" "$root/build/mint/log.json" 2>/dev/null
    cp -f "$log" "$root/build/mint/bochka.log" 2>/dev/null
    echo "kept mint's log and the server log in $root/build/mint"
  fi
  rm -rf "$work" 2>/dev/null
  # In the trap, where an edit above cannot step over it: a run that executed no case at all exits
  # zero on its own and reads exactly like a pass.
  if [ "$ran" = no ]; then
    echo "mint executed no case: that is a failure, not an empty result" >&2
    [ "$status" -eq 0 ] && status=1
  fi
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; mint runs as a container" >&2; exit 3; }
command -v python3 >/dev/null || { echo "python3 reads mint's log" >&2; exit 3; }

echo "building the distribution"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

mkdir -p "$work/data" "$work/log"
chmod 777 "$work/log"
BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_DATA_DIR="$work/data" \
  "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!

for _ in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.2
done
if ! (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then
  echo "bochka did not come up; log follows" >&2; cat "$log" >&2; exit 3
fi
echo "bochka is up on 127.0.0.1:$PORT"
echo

# `RUN_ON_FAIL=1`, because the number wanted here is how many cases pass rather than which SDK
# stopped first: without it one failing case hides everything behind it in the same SDK.
docker run --rm --network host -v "$work/log:/mint/log" \
  -e SERVER_ENDPOINT="127.0.0.1:$PORT" -e ACCESS_KEY="$KEY" -e SECRET_KEY="$SECRET" \
  -e ENABLE_HTTPS=0 -e RUN_ON_FAIL=1 \
  "$IMAGE" "$@" 2>&1 | tail -5

ran=yes
python3 "$root/ci/mint_scope.py" "$work/log/log.json" "$root/ci/mint-scope.txt"
