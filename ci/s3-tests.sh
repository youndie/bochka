#!/usr/bin/env bash
# Runs somebody else's compatibility suite against bochka and prints a number.
#
# `ceph/s3-tests` is the only metric this project has that means anything outside its own
# repository: "how much of it a single-process JVM store passes" is comparable with other
# implementations, which is true of no benchmark bochka could run on itself.
#
# The number is the point, not a green run. Most of the suite exercises things bochka does not have
# and may never have — versioning, ACLs, lifecycle, IAM — so the score starts low and the useful
# question is which direction it moves. **The count of tests that ran is printed next to the
# percentage**: a rising score and a shrinking suite look identical otherwise (Р6).
set -uo pipefail

readonly PORT=${BOCHKA_PORT:-19001}
readonly IMAGE=python:3.11-slim
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
log="$work/bochka.log"

# Whether this run ever printed a number. The check at the bottom — "nothing ran is a failure
# rather than a score" — only fires if control reaches it, and a `set -u` abort part way through
# does not: an edit to the block below once ended a quoted string early, the rest of it ran in the
# host shell, and the script died on an unbound variable having reported nothing at all. It exited
# without a score and without complaining, which is the exact shape of failure this file exists to
# refuse. So the guard moves into the exit trap, where nothing can step over it.
scored=no

# Whether anything about this run is worth keeping the evidence for. Set to `yes` when the run
# errors, when a failure is unclassified, or when no score came out at all.
keep=no

# Where the evidence goes when it is kept. Inside `build/` because that is already ignored, and
# under a fixed name because the point is that the next command can find it without being told.
readonly EVIDENCE="$root/build/s3-tests"

# The server's own log, the raw pytest output and the junit XML live in a temp directory that this
# trap removes — which is right for a green run and exactly wrong for a red one. A whole afternoon
# went into guessing which request a fixture had been refused, because the answer was in a log that
# had been deleted a second after it was written. Diagnosis that has to re-run the suite to look at
# it again is diagnosis by guesswork.
preserve() {
  [ "$keep" = yes ] || return 0
  mkdir -p "$EVIDENCE" 2>/dev/null || return 0
  for name in bochka.log pytest.out results.xml suite-revision; do
    [ -f "$work/$name" ] && cp "$work/$name" "$EVIDENCE/$name" 2>/dev/null
  done
  echo "kept the server log and the raw output in $EVIDENCE" >&2
}

cleanup() {
  status=$?
  # Killed **and waited for**, in that order and both before the copy. The server writes its log to
  # a file, so `System.out` is buffered rather than line-flushed, and a `kill` that does not wait
  # leaves the last thousands of lines in a buffer nobody will read: the first version of this
  # preserved a log with the startup banner and nothing else, which is worse than preserving
  # nothing — it looks like the server handled no requests.
  if [ -n "${server_pid:-}" ]; then
    kill "$server_pid" 2>/dev/null
    wait "$server_pid" 2>/dev/null
  fi
  if [ "$scored" = no ]; then
    echo "this run ended without producing a score, which is a failure rather than a zero" >&2
    [ "$status" -eq 0 ] && status=1
    keep=yes
  fi
  # Before the removal, and deliberately: a copy made after `rm -rf` copies nothing, and would do
  # it silently.
  preserve
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; the suite cannot run" >&2; exit 3; }

"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

# `BOCHKA_LOG=1` is on for the whole run, and it is the half of this that matters: a preserved log
# with nothing in it but the startup banner answers no question at all. It costs a few megabytes of
# a file that is thrown away on a green run.
BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_DATA_DIR="$work/data" BOCHKA_LOG=1 \
  BOCHKA_KEYS="s3main:s3mainsecret,s3alt:s3altsecret,s3tenant:s3tenantsecret" \
  "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!

for _ in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.2
done
(exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null || { echo "bochka did not come up" >&2; cat "$log" >&2; exit 3; }

# The configuration is built from the suite's own sample rather than written by hand: its loader
# insists on sections that have nothing to do with S3 — `iam`, `webidentity` — and fails collection
# of the whole file if one is missing. Patching the sample keeps that list the suite's problem.
cat > "$work/make-conf.py" <<'PYCONF'
import configparser, sys
cfg = configparser.RawConfigParser()
cfg.read("/s3-tests/s3tests.conf.SAMPLE")
cfg.set("DEFAULT", "host", "127.0.0.1")
cfg.set("DEFAULT", "port", sys.argv[1])
cfg.set("DEFAULT", "is_secure", "False")
cfg.set("DEFAULT", "ssl_verify", "False")
cfg.set("fixtures", "bucket prefix", "bochka-{random}-")
for section, key, secret in (
    ("s3 main", "s3main", "s3mainsecret"),
    ("s3 alt", "s3alt", "s3altsecret"),
    ("s3 tenant", "s3tenant", "s3tenantsecret"),
):
    if not cfg.has_section(section):
        cfg.add_section(section)
    cfg.set(section, "access_key", key)
    cfg.set(section, "secret_key", secret)
    cfg.set(section, "user_id", key)
    # The suite asks the server for a bucket's location and compares it with this. It is the name
    # of the region the deployment runs in, not a constant, so it has to agree with BOCHKA_REGION.
    # Set per section rather than in DEFAULT: the sample sets it inside the sections, and a value
    # there wins over the default, so a DEFAULT-only version of this line changed nothing.
    cfg.set(section, "api_name", "us-east-1")
with open("/work/s3tests.conf", "w") as out:
    cfg.write(out)
PYCONF

echo "running ceph/s3-tests (this pulls the suite and takes a few minutes)"
# `BOCHKA_S3TESTS_K` narrows the run to a `-k` expression and turns tracebacks on, which is how a
# handful of failures get looked at without waiting three minutes for the other seven hundred.
docker run --rm --network host -v "$work:/work" \
  -e SELECT="${BOCHKA_S3TESTS_K:-}" \
  -e TRACEBACK="$([ -n "${BOCHKA_S3TESTS_K:-}" ] && echo short || echo no)" \
  -e TAIL="$([ -n "${BOCHKA_S3TESTS_K:-}" ] && echo 120 || echo 5)" \
  "$IMAGE" bash -c '
  set -e
  apt-get update -qq >/dev/null 2>&1 && apt-get install -qq -y git >/dev/null 2>&1
  git clone -q --depth 1 https://github.com/ceph/s3-tests.git /s3-tests
  cd /s3-tests
  # pytest-timeout on top of the requirements the suite declares. A test that waits on something
  # bochka never answers otherwise burns a socket timeout, and a few dozen of those eat the whole
  # budget before pytest can print a summary — the one thing this script exists to produce.
  #
  # Sixty seconds and not twenty, and that was measured rather than nudged. `test_multipart_get_part`
  # started timing out once it got far enough to download anything, and the split is: the server
  # does the whole upload, completion and download of sixteen mebibytes in 0.32 s, while the
  # comparison loop inside the test — `data = data[len(chunk):]` over a sixteen-mebibyte string,
  # once per kilobyte chunk — costs 11.57 s. A twenty-second bound on that measures quadratic
  # slicing in the harness under whatever else the runner is doing, and calls the result a server
  # failure. A hang is still a hang at sixty.
  #
  # The note below is not decoration. The first draft of this paragraph wrote "the test" with a
  # possessive apostrophe, which ended the string and handed the rest of it to the host shell.
  #
  # `signal` and not `thread`: the thread method kills the whole pytest process when a test hangs
  # in a way it cannot interrupt, and a run that dies has no summary — which this script then
  # correctly reports as "nothing ran". One hang must cost one test, not the score.
  #
  # Note for whoever edits this block: it is a single-quoted shell string, so an apostrophe in a
  # comment ends it. That is not hypothetical, it happened here.
  pip install -q -r requirements.txt pytest-timeout >/dev/null 2>&1
  git rev-parse --short HEAD > /work/suite-revision
  python /work/make-conf.py '"$PORT"'
  S3TEST_CONF=/work/s3tests.conf timeout 5400 python -m pytest s3tests/functional/test_s3.py \
    -p no:cacheprovider -q --no-header -rN --continue-on-collection-errors \
    --timeout=60 --timeout-method=signal --junit-xml=/work/results.xml \
    ${SELECT:+-k} ${SELECT:+"$SELECT"} --tb=${TRACEBACK} \
    > /work/pytest.out 2>&1 || true
  tail -${TAIL} /work/pytest.out
' 2>&1 | tail -"$([ -n "${BOCHKA_S3TESTS_K:-}" ] && echo 130 || echo 6)"

echo
echo "suite revision: $(cat "$work/suite-revision" 2>/dev/null || echo unknown)"
# The last line pytest prints, whatever it says: "1 skipped, 837 errors in 21s" is as much a
# result as "12 passed". An earlier version of this grep looked for the word "passed" and reported
# "nothing" for a run of 838 tests — the score was zero and the script said it had not run.
summary=$(grep -E 'in [0-9.]+s' "$work/pytest.out" 2>/dev/null | tail -1)
echo "pytest says: ${summary:-nothing}"

# Parsed with python rather than sed: the first version of this counted "837 errors" as seven,
# because a regex that anchors on the last digits of a number is right until it is not.
read -r passed failed errors < <(python3 - "$summary" <<'PARSE'
import re, sys
line = sys.argv[1] if len(sys.argv) > 1 else ""
def count(word):
    match = re.search(r"(\d+) " + word, line)
    return int(match.group(1)) if match else 0
print(count("passed"), count("failed"), count("error"))
PARSE
)
ran=$((passed + failed + errors))

echo
if [ "$ran" -eq 0 ]; then
  echo "nothing ran, which is a failure rather than a score" >&2
  tail -20 "$work/pytest.out" >&2
  exit 1
fi
printf 'ceph/s3-tests: %d of %d passed (%d%%), %d failed, %d errored\n' \
  "$passed" "$ran" $((passed * 100 / ran)) "$failed" "$errors"
scored=yes
# An errored case is not a failing case: it never reached its own assertions, which almost always
# means a fixture was refused something. That is the run whose log is worth keeping.
if [ "$errors" -gt 0 ]; then keep=yes; fi
echo "the count of tests that ran is part of the number: a rising score and a shrinking suite look the same"

# Why the rest fail, grouped. A bare percentage says how far there is to go and nothing about what
# the distance is made of — and the difference between "does not have versioning" and "has a defect"
# is the whole of it (M-67, Risk 2). The rules live in ci/s3-tests-scope.txt so that a failure
# nobody has classified shows up as `unclassified` rather than quietly joining a bucket.
if [ -f "$work/results.xml" ]; then
  echo
  classification=$(python3 "$root/ci/s3_tests_scope.py" "$work/results.xml" "$root/ci/s3-tests-scope.txt" \
    "${BOCHKA_FAILED_OUT:-}")
  echo "$classification"
  # A failure nobody has classified is the other kind of run worth keeping: either the rules have
  # gone stale or something new broke, and both are answered from the log rather than from here.
  if echo "$classification" | grep -q "unclassified"; then keep=yes; fi
fi

# `S3TESTS_KEEP_LOG=1` keeps it regardless, for the run that is green and still surprising.
#
# **Not** `BOCHKA_KEEP_LOG`, and that is not taste: the server reads its whole configuration out of
# `BOCHKA_*` and refuses a name it does not know. It inherits this script's environment, so a knob
# invented here under that prefix makes the server print its usage and exit — the run then keeps a
# log holding the usage text and nothing else, which reads as "the server handled no requests".
#
# `if` rather than a bare `&&`, because `&&` as the last statement decides the script's exit status
# and a green run would end in `1` for the sole reason that the variable was unset.
if [ "${S3TESTS_KEEP_LOG:-}" = 1 ]; then keep=yes; fi
true
