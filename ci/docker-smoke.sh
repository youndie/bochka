#!/usr/bin/env bash
# What the image does that the gate cannot see.
#
# `./gradlew check` runs the code; none of it runs the *image*, and everything below has been a
# real production failure in somebody's project: a container writing as root onto a bind mount, a
# `JAVA_OPTS` from a base image silently replacing the runtime profile, a health check that
# reports a hung JVM as healthy, a configuration variable that was ignored because it was misspelt.
#
#   ./ci/docker-smoke.sh
set -uo pipefail

readonly IMAGE=${BOCHKA_IMAGE:-bochka:smoke}
readonly PORT=${BOCHKA_PORT:-19002}
readonly NAME=bochka-smoke

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
passed=0; failed=0

pass() { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail() { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null || docker run --rm -v "$work:/w" --entrypoint "" alpine:latest rm -rf /w/. 2>/dev/null
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed" >&2; exit 3; }

echo "building the distribution and the image"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }
docker build -q -t "$IMAGE" "$root" >/dev/null || { echo "docker build failed" >&2; exit 3; }

mkdir -p "$work/data"
# Deliberately owned by whoever runs this script, and deliberately not chowned to the container's
# user: that is the situation a bind mount actually produces.
docker run -d --name "$NAME" -p "$PORT:9000" \
  -v "$work/data:/var/lib/bochka" \
  -u "$(id -u):$(id -g)" \
  -e BOCHKA_KEYS=smokekey:smokesecret \
  -e BOCHKA_LOG=1 \
  -e JAVA_OPTS="-Xmx3G" \
  "$IMAGE" >/dev/null 2>&1

for _ in $(seq 1 60); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break
  sleep 0.5
done

if docker ps --filter "name=$NAME" --filter status=running -q | grep -q .; then
  pass "the container is running"
else
  fail "the container is not running"
  docker logs "$NAME" 2>&1 | tail -20
  echo; printf 'passed %d, failed %d\n' "$passed" "$failed"; exit 1
fi

# --- the runtime profile is the one that was measured -------------------------------------------
#
# A base image or an orchestrator that sets JAVA_OPTS must not be able to replace the heap the
# object ceiling is derived from. If it can, the published number is about a process nobody runs.
if docker exec "$NAME" sh -c 'cat /proc/1/cmdline | tr "\0" "\n"' 2>/dev/null | grep -q -- "-Xmx512M"; then
  pass "the shipped heap survived JAVA_OPTS"
else
  fail "JAVA_OPTS replaced the runtime profile"
  docker exec "$NAME" sh -c 'cat /proc/1/cmdline | tr "\0" " "' 2>/dev/null
fi

# --- the volume belongs to the process, not to root ---------------------------------------------
docker exec "$NAME" sh -c 'echo probe > /var/lib/bochka/.probe' >/dev/null 2>&1 &&
  pass "the data directory is writable by the container's user" ||
  fail "the container cannot write to its own data directory"

if [ -n "$(find "$work/data" -maxdepth 1 -user root -print -quit 2>/dev/null)" ]; then
  fail "the container left root-owned files on the host"
else
  pass "nothing on the host volume is owned by root"
fi

# --- the configuration reached the process ------------------------------------------------------
docker logs "$NAME" 2>&1 | grep -q "smokekey" &&
  pass "the access key from the environment is in use" ||
  fail "the access key from the environment did not reach the process"

# --- an unknown setting stops the process rather than being ignored -----------------------------
#
# The failure this prevents: a misspelt BOCHKA_DATA_DIR means the objects are in a temporary
# directory and the server says nothing at all about it.
typo=$(docker run --rm -e BOCHKA_DATADIR=/tmp/x "$IMAGE" 2>&1)
if echo "$typo" | grep -q "unknown setting" && echo "$typo" | grep -q "data.dir"; then
  pass "a misspelt setting stops the process and names the one it meant"
else
  fail "a misspelt setting was ignored"
  echo "$typo" | head -3
fi

# --- the health check distinguishes a live server from a hung one -------------------------------
#
# A TCP connect succeeds against a JVM that is stuck in a full GC and answering nothing, which is
# why the check is a request with an answer rather than an open socket.
status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/")
if [ "$status" = "403" ] || [ "$status" = "200" ]; then
  pass "an unauthenticated request gets an answer, not a socket ($status)"
else
  fail "the server did not answer a request (got '$status')"
fi

# --- it stores something, which is the point ----------------------------------------------------
if docker run --rm --network host \
     -e AWS_ACCESS_KEY_ID=smokekey -e AWS_SECRET_ACCESS_KEY=smokesecret \
     -e AWS_DEFAULT_REGION=us-east-1 -e AWS_EC2_METADATA_DISABLED=true \
     -v "$work:/work" --entrypoint "" amazon/aws-cli:latest sh -c "
       echo hello > /work/probe.txt &&
       aws --endpoint-url http://127.0.0.1:$PORT s3 mb s3://smoke &&
       aws --endpoint-url http://127.0.0.1:$PORT s3 cp /work/probe.txt s3://smoke/probe.txt &&
       aws --endpoint-url http://127.0.0.1:$PORT s3 cp s3://smoke/probe.txt /work/back.txt" >/dev/null 2>&1 &&
   [ "$(cat "$work/back.txt" 2>/dev/null)" = "hello" ]; then
  pass "a round trip through the image works"
else
  fail "a round trip through the image does not work"
fi

# --- and what it stored is on the volume, where it can outlive the container --------------------
docker rm -f "$NAME" >/dev/null 2>&1
if [ -f "$work/data/index.log" ] && [ -d "$work/data/data" ]; then
  pass "the index and the objects are on the volume"
else
  fail "the container kept its data somewhere that dies with it"
fi

# --- and a new container on the same volume finds them ------------------------------------------
docker run -d --name "$NAME" -p "$PORT:9000" -v "$work/data:/var/lib/bochka" -u "$(id -u):$(id -g)" \
  -e BOCHKA_KEYS=smokekey:smokesecret "$IMAGE" >/dev/null 2>&1
for _ in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 0.5; done
if docker run --rm --network host \
     -e AWS_ACCESS_KEY_ID=smokekey -e AWS_SECRET_ACCESS_KEY=smokesecret \
     -e AWS_DEFAULT_REGION=us-east-1 -e AWS_EC2_METADATA_DISABLED=true \
     -v "$work:/work" --entrypoint "" amazon/aws-cli:latest \
     aws --endpoint-url "http://127.0.0.1:$PORT" s3 cp s3://smoke/probe.txt /work/again.txt >/dev/null 2>&1 &&
   [ "$(cat "$work/again.txt" 2>/dev/null)" = "hello" ]; then
  pass "a fresh container reads what the last one stored"
else
  fail "a fresh container did not find the objects on the volume"
fi

echo
printf 'passed %d, failed %d\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
