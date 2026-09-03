#!/usr/bin/env bash
# What the image does that the gate cannot see.
#
# `./gradlew check` runs the code; none of it runs the *image*, and everything below has been a
# real production failure in somebody's project: a container writing as root onto a bind mount, a
# runtime profile that is not the one that was measured, a health check that reports a hung JVM as
# healthy, a configuration variable that was ignored because it was misspelt, a container that
# ignores SIGTERM and is killed mid-write ten seconds later.
#
# Some of it is about the image's *configuration* rather than its behaviour — the user it declares,
# the labels it carries. Nothing else in this repository looks at those, and an orchestrator does.
#
#   ./ci/docker-smoke.sh
set -uo pipefail

readonly IMAGE=${BOCHKA_IMAGE:-bochka:smoke}
readonly PORT=${BOCHKA_PORT:-19002}
readonly NAME=bochka-smoke
readonly RO_PORT=$((PORT + 1))
readonly RO_NAME=bochka-smoke-ro

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
passed=0; failed=0

pass() { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail() { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }

# The object ceiling the server prints at startup is `maxMemory() * 0.5 / 650`, so it is a reading
# of the heap the JVM is *actually* running with — the only oracle available in a JRE image, which
# ships no jcmd. Extra `docker run` arguments are passed through.
ceiling() {
  local n=
  docker rm -f "$NAME-ceiling" >/dev/null 2>&1
  docker run -d --rm --name "$NAME-ceiling" "$@" "$IMAGE" >/dev/null 2>&1
  for _ in $(seq 1 40); do
    n=$(docker logs "$NAME-ceiling" 2>&1 | sed -n 's/^object ceiling: \([0-9]*\).*/\1/p')
    [ -n "$n" ] && break
    sleep 0.5
  done
  docker logs "$NAME-ceiling" >"$work/ceiling.log" 2>&1
  docker rm -f "$NAME-ceiling" >/dev/null 2>&1
  printf '%s' "$n"
}

cleanup() {
  docker rm -f "$NAME" "$RO_NAME" "$NAME-ceiling" "$NAME-note" >/dev/null 2>&1
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null || docker run --rm -v "$work:/w" --entrypoint "" alpine:latest rm -rf /w/. 2>/dev/null
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed" >&2; exit 3; }

echo "building the distribution and the image"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }
docker build -q -t "$IMAGE" "$root" >/dev/null || { echo "docker build failed" >&2; exit 3; }

# --- the image declares a numeric user ----------------------------------------------------------
#
# `USER bochka` and `USER 1000:1000` are the same process and a different image. A kubelet asked
# for `runAsNonRoot: true` without an explicit `runAsUser` has to decide whether the image runs as
# root, it cannot resolve a name against /etc/passwd inside an image it has not started, and it
# refuses to start the container rather than guess. A number it can read.
declared_user=$(docker inspect "$IMAGE" --format '{{.Config.User}}' 2>/dev/null)
case $declared_user in
  [0-9]*) pass "the image declares a numeric user ($declared_user)" ;;
  *)      fail "the image declares '$declared_user', which runAsNonRoot cannot resolve" ;;
esac

# --- the labels describe this project, not the base image ---------------------------------------
#
# Labels are inherited, so "no labels" is not what an unlabelled image has: it has somebody else's.
# Every tool that reads them — a registry's package page, a scanner, a dependency bot — is then
# told that bochka is the base distribution at the base distribution's version.
label() { docker inspect "$IMAGE" --format "{{index .Config.Labels \"org.opencontainers.image.$1\"}}" 2>/dev/null; }
if [ "$(label title)" = "bochka" ] && [ "$(label licenses)" = "MIT" ]; then
  pass "the image is labelled as bochka, not as its base"
else
  fail "the image carries the base image's identity (title='$(label title)' version='$(label version)')"
fi
# `source` is also the mechanism a GHCR package is linked to its repository by; without it the
# package page does not know which repository built it.
case $(label source) in
  *github.com/*) pass "the image names the repository it was built from" ;;
  *)             fail "the image does not name its source repository" ;;
esac

mkdir -p "$work/data"
# Deliberately owned by whoever runs this script, and deliberately not chowned to the container's
# user: that is the situation a bind mount actually produces.
docker run -d --name "$NAME" -p "$PORT:9000" \
  -v "$work/data:/var/lib/bochka" \
  -u "$(id -u):$(id -g)" \
  -e BOCHKA_KEYS=smokekey:smokesecret \
  -e BOCHKA_LOG=1 \
  "$IMAGE" >/dev/null 2>&1

for _ in $(seq 1 60); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break
  sleep 0.5
done

if docker ps --filter "name=$NAME" --filter status=running -q | grep . >/dev/null; then
  pass "the container is running"
else
  fail "the container is not running"
  docker logs "$NAME" 2>&1 | tail -20
  echo; printf 'passed %d, failed %d\n' "$passed" "$failed"; exit 1
fi

# --- the runtime profile is the one that was measured -------------------------------------------
#
# This check used to grep /proc/1/cmdline for `-Xmx512M` and pass. It was green and it was false.
# The generated start script assembles `$DEFAULT_JVM_OPTS $JAVA_OPTS $BOCHKA_APP_OPTS` in that
# order, so with JAVA_OPTS set both flags are on the command line and HotSpot honours the last
# one. The substring survives; the setting it stands for does not. Measured: the ceiling the
# process prints moves 399215 -> 2395290 under `-e JAVA_OPTS=-Xmx3G`, with `-Xmx512M` still there
# in the cmdline for the grep to find. Hence a check that reads the effect instead.
shipped=$(ceiling)
explicit=$(ceiling -e JAVA_OPTS=-Xmx512M)
forced=$(ceiling -e JAVA_OPTS=-Xmx3G)

if [ -n "$shipped" ] && [ "$shipped" = "$explicit" ]; then
  pass "an untouched environment runs the shipped heap (ceiling $shipped)"
else
  fail "the shipped profile is not 512M of heap (ceiling $shipped, -Xmx512M gives $explicit)"
fi

# The number above is also a **published** number, and until M21 was checked again nothing compared
# the two. 399 215 stands in README.md, in CLAUDE.md, in the KDoc of build.gradle.kts and in the
# chart's `bochka.derivedCeiling` -- the last of which is what `helm install` prints to an operator
# as "what this appVersion prints on its first line". Its sibling 99 816 is checked: the chart
# harness reads it out of the pod's log to prove the small profile actually started. So a heap
# profile, a collector or an index entry size that moved would leave every published number stale
# with only the small profile's guard red. Both numbers are read out of the documents rather than
# typed again here: a check carrying its own copy of a number is one more place for it to drift.
published=$(sed -n 's/^| `default`, what ships |[^|]*| \*\*\([0-9 ]*\)\*\*.*/\1/p' "$root/README.md" | tr -d ' ')
charted=$(sed -n 's/.*{{ else }}\([0-9][0-9]*\){{ end -}}.*/\1/p' "$root/deploy/helm/bochka/templates/_helpers.tpl")

if [ -z "$published" ] || [ -z "$charted" ]; then
  fail "the published ceiling could not be read back (README '$published', chart '$charted'): the table or the helper changed shape"
elif [ "$shipped" = "$published" ] && [ "$shipped" = "$charted" ]; then
  pass "the ceiling the process prints is the one README and the chart publish ($shipped)"
else
  fail "the process prints $shipped, README says $published, the chart quotes $charted"
fi

# The other row of the same table, and it was in the same position: published in four places and
# compared with a process in none. The small profile is the default list with a quarter of the heap
# (M33), so a run under `-Xmx128M` derives exactly the ceiling that profile ships -- which is what
# lets this be asked here, without the cluster the profile's own entry point needs.
small_published=$(sed -n 's/^| `small` |[^|]*| \*\*\([0-9 ]*\)\*\*.*/\1/p' "$root/README.md" | tr -d ' ')
small_charted=$(sed -n 's/.*heapProfile "small" }}\([0-9][0-9]*\){{ else }}.*/\1/p' \
  "$root/deploy/helm/bochka/templates/_helpers.tpl")
small_run=$(ceiling -e JAVA_OPTS=-Xmx128M)

if [ -z "$small_published" ] || [ -z "$small_charted" ]; then
  fail "the small profile's published ceiling could not be read back (README '$small_published', chart '$small_charted')"
elif [ "$small_run" = "$small_published" ] && [ "$small_run" = "$small_charted" ]; then
  pass "the small profile's ceiling is the one README and the chart publish ($small_run)"
else
  fail "a 128M heap derives $small_run, README says $small_published, the chart quotes $small_charted"
fi

# Not an approval of the override — a record of it, so that the one place it matters is not left to
# be discovered. Nothing that wraps this image may offer a JAVA_OPTS knob: the object ceiling is
# derived from the heap, so such a knob reads as "more objects" and is a promise made by the wrapper
# rather than by the server. If this check ever goes red the profile has become authoritative, which
# is an improvement — update the check, and the claim in deploy/README.md with it.
if [ -n "$forced" ] && [ "$forced" != "$shipped" ]; then
  pass "JAVA_OPTS still overrides the shipped heap ($shipped -> $forced), so nothing may expose it"
else
  fail "JAVA_OPTS no longer overrides the shipped heap ($shipped -> $forced): re-check what does"
fi

# The second name, and the one that used to be safe by accident. `BOCHKA_APP_OPTS` is the start
# script's own knob -- Gradle derives it from the application name and documents it in the script it
# generates -- and the server used to refuse to start with it set, because it begins with `BOCHKA_`.
# That refusal was a bug (M-142) and it is gone, so this name now does what JAVA_OPTS does and does
# it without a word. Checked separately from JAVA_OPTS because the two failed differently and would
# fail differently again: one was loud and wrong, the other silent and right.
appopts=$(ceiling -e BOCHKA_APP_OPTS=-Xmx3G)
if [ -n "$appopts" ] && [ "$appopts" != "$shipped" ]; then
  pass "BOCHKA_APP_OPTS starts the server and overrides the heap ($shipped -> $appopts)"
else
  fail "BOCHKA_APP_OPTS did not override the shipped heap ($shipped -> $appopts): did the server refuse it?"
fi

# --- the collector is announced, because two published numbers depend on it ---------------------
#
# M-156. The object ceiling above is `maxMemory() * 0.5 / 650`, and `maxMemory()` is a property of
# the collector: 455, 494 and 512 MiB at the same -Xmx512M under Parallel, Serial and G1. A wrapper
# that swaps the collector moves a number in the README without touching it, so the log says which
# one is running. Checked in the image because that is where somebody would swap it.
#
# This check went red on CI while its own message printed the very line it called missing, and the
# reason was neither the log nor the server: `set -o pipefail` with `grep -q`. `grep -q` exits at
# the first match, the writer on the left of the pipe gets `SIGPIPE`, and the pipeline's status
# becomes 141 — a **false negative that depends on how much the writer had left to say**. Measured:
# `yes | grep -q y` exits 141 under pipefail, `printf 'a\n' | grep -q a` exits 0, which is why this
# passed for months. Every `| grep -q` in ci/ was the same trap and is now a plain `grep` with its
# output thrown away: it reads to the end, so nobody is left writing into a closed pipe.
#
# Read once into a variable besides, so the test and the message cannot disagree about the evidence.
collector=""
for _ in $(seq 1 25); do
  collector=$(docker logs "$NAME" 2>&1 | grep -i '^collector:' | head -1)
  [ -n "$collector" ] && break
  sleep 0.2
done
case "$collector" in
  "collector: Serial at"*)
    pass "the log names the collector the numbers were measured under"
    ;;
  "")
    fail "the startup log never named a collector at all, in five seconds of waiting"
    ;;
  *)
    fail "the startup log names another collector, and two published numbers move with it: $collector"
    ;;
esac

# M-157. And it is a note rather than a refusal: a person may raise the heap, they may not be left
# to discover the pause. `-Xmx3G` is outside the measured envelope, and the process must still come
# up — a check that only proved the warning would pass equally well on a server that refused.
if noisy=$(ceiling -e JAVA_OPTS=-Xmx3G) && [ -n "$noisy" ]; then
  pass "a heap beyond the measured envelope still starts (ceiling $noisy)"
else
  fail "a heap beyond the measured envelope did not start"
fi

# The other half of M-157, and until M22 was checked again it was the missing one. "Not a refusal"
# was asserted above; "loud" was asserted nowhere. `GcProfile.beyondWhatWasMeasured()` has its own
# unit tests, but they test the string the function *returns* -- deleting the one line in Main that
# prints it leaves every one of them green and this script green with them, which is the whole
# shape of the defect the milestone was written against: a note nobody is told.
#
# The note is read from a run of its own rather than from the one that produced the ceiling above:
# it is printed *after* the ceiling line, so a reader that stops at the ceiling can stop one line
# early. Waited for by `housekeeping:`, which the server prints after both. The size is not typed
# in either -- under -Xmx3G the JVM reports 2969 MiB, not 3072, and a check carrying its own idea
# of the number would be testing arithmetic instead of the log.
docker rm -f "$NAME-note" >/dev/null 2>&1
docker run -d --rm --name "$NAME-note" -e JAVA_OPTS=-Xmx3G "$IMAGE" >/dev/null 2>&1
for _ in $(seq 1 40); do
  docker logs "$NAME-note" >"$work/note.log" 2>&1
  grep '^housekeeping:' "$work/note.log" >/dev/null 2>&1 && break
  sleep 0.5
done
docker rm -f "$NAME-note" >/dev/null 2>&1

if grep '^NOTE: heap: .* is beyond what this distribution was measured on' "$work/note.log" >/dev/null 2>&1; then
  pass "the heap outside the envelope is announced in the log, not only computed"
else
  fail "nothing in the log said the heap was outside the measured envelope"
  grep -E '^(object ceiling|collector|NOTE):' "$work/note.log" 2>/dev/null | sed 's/^/          /'
fi

# And the negative that makes the positive worth having: a note printed on every start is not a
# note. The shipped profile is inside the envelope by construction, so its log must be silent about
# it -- read from the long-running container, which runs with no heap override at all.
if docker logs "$NAME" 2>&1 | grep '^NOTE:' >/dev/null 2>&1; then
  fail "the shipped profile announces itself as outside its own envelope"
  docker logs "$NAME" 2>&1 | grep '^NOTE:' | sed 's/^/          /'
else
  pass "the shipped profile says nothing about the envelope, because it is inside it"
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
#
# Polled rather than read once, and that was a real red build rather than caution. The banner is
# printed **after** the socket is bound, so the readiness loop above — `curl` until the server
# answers — proves the server is up and proves nothing about the log being written. Read once, this
# went red on a loaded runner and green everywhere else, which is the worst state a check can be in:
# the next genuine failure gets waved through as "that one flakes".
#
# The second half of the fix is the evidence. This printed nothing on failure, so the one run that
# knew what the log held threw it away — the same rule the suite harness already learned, that
# diagnosis which has to re-run the thing is diagnosis by guesswork.
found=no
for _ in $(seq 1 40); do
  if docker logs "$NAME" 2>&1 | grep "smokekey" >/dev/null; then found=yes; break; fi
  sleep 0.5
done
if [ "$found" = yes ]; then
  pass "the access key from the environment is in use"
else
  fail "the access key from the environment did not reach the process"
  docker logs "$NAME" 2>&1 | tail -20
fi

# --- an unknown setting stops the process rather than being ignored -----------------------------
#
# The failure this prevents: a misspelt BOCHKA_DATA_DIR means the objects are in a temporary
# directory and the server says nothing at all about it.
typo=$(docker run --rm -e BOCHKA_DATADIR=/tmp/x "$IMAGE" 2>&1)
if echo "$typo" | grep "unknown setting" >/dev/null && echo "$typo" | grep "data.dir" >/dev/null; then
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

# The handle an orchestrator uses (M-143), checked in the image because that is where a probe meets
# it. In-process tests prove the route and ci/helm-chart.sh proves a kubelet accepts it; neither
# would notice an image that ships an older jar than the tree it was built from.
health_code=$(curl -s -o "$work/health.txt" -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/-/healthy")
health_body=$(tr -d '\r\n' <"$work/health.txt")
if [ "$health_code" = "200" ] && [ "$health_body" = "ok" ]; then
  pass "an unsigned GET /-/healthy answers 200 with a body a person can read"
else
  fail "the health handle answered '$health_code' with body '$health_body'"
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

# --- a read-only root filesystem, which is what a hardened pod gives it -------------------------
#
# Everything the server writes has to be under the data directory and nowhere else. A JVM that
# wants scratch space outside it fails here and nowhere else: on a writable root it silently gets
# what it asked for. The upload is 20 MB so that it is multipart — the part-assembly path is the
# one most likely to reach for a temporary file — and it is compared byte for byte, because a
# short read is a successful command with the wrong content.
mkdir -p "$work/ro"
head -c 20000000 /dev/urandom > "$work/ro.bin"
docker run -d --name "$RO_NAME" --read-only -p "$RO_PORT:9000" \
  -v "$work/ro:/var/lib/bochka" -u "$(id -u):$(id -g)" \
  -e BOCHKA_KEYS=smokekey:smokesecret "$IMAGE" >/dev/null 2>&1
for _ in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$RO_PORT/" && break; sleep 0.5; done
if docker run --rm --network host \
     -e AWS_ACCESS_KEY_ID=smokekey -e AWS_SECRET_ACCESS_KEY=smokesecret \
     -e AWS_DEFAULT_REGION=us-east-1 -e AWS_EC2_METADATA_DISABLED=true \
     -v "$work:/work" --entrypoint "" amazon/aws-cli:latest sh -c "
       aws --endpoint-url http://127.0.0.1:$RO_PORT s3 mb s3://readonly &&
       aws --endpoint-url http://127.0.0.1:$RO_PORT s3 cp /work/ro.bin s3://readonly/ro.bin &&
       aws --endpoint-url http://127.0.0.1:$RO_PORT s3 cp s3://readonly/ro.bin /work/ro-back.bin" >/dev/null 2>&1 &&
   cmp -s "$work/ro.bin" "$work/ro-back.bin"; then
  pass "a multipart round trip works with a read-only root filesystem"
else
  fail "the server needs to write outside its data directory"
  docker logs "$RO_NAME" 2>&1 | tail -10
fi
docker rm -f "$RO_NAME" >/dev/null 2>&1

# --- SIGTERM is what an orchestrator sends, and it has to be enough -----------------------------
#
# The start script `exec`s, so the JVM is PID 1 and the signal reaches it, and Main closes the
# server from a shutdown hook. Measured rather than assumed: a process that ignores SIGTERM is
# killed by `docker stop`'s timeout ten seconds later instead, with whatever it was writing
# unfinished, and the exit code is what tells the two apart — 143 is 128 + SIGTERM, 137 is
# 128 + SIGKILL. The volume checks below then read what a graceful stop actually left behind.
stop_started=$(date +%s)
docker stop "$NAME" >/dev/null 2>&1
stop_took=$(($(date +%s) - stop_started))
stop_code=$(docker inspect "$NAME" --format '{{.State.ExitCode}}' 2>/dev/null)
if [ "$stop_code" = "143" ] && [ "$stop_took" -lt 10 ]; then
  pass "SIGTERM stops the server in ${stop_took}s, exit $stop_code"
else
  fail "SIGTERM did not stop the server cleanly (exit '$stop_code' after ${stop_took}s)"
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
