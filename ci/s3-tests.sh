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
# How long a lifecycle "day" lasts on both sides of this run; see the note by the server start.
# `S3TESTS_LC_DAY` overrides it — not `BOCHKA_*`, because the server refuses a name in its own
# namespace that it does not know, and would print its usage instead of starting.
readonly LC_DAY=${S3TESTS_LC_DAY:-5}
# The per-case ceiling, and it is named here rather than written into the pytest line because it
# has to agree with [LC_DAY] and with the check that reads the results afterwards. Two settings
# obliged to agree are the first way to make them disagree — the same reasoning that derives the
# sweep period from the day rather than putting it beside it (M23).
#
# The arithmetic is fixed by the suite: the longest case sleeps ten lifecycle intervals, so
# `LC_DAY` × 10 has to stay under this. At five that is fifty seconds against sixty — a margin of
# twenty percent, and on a loaded machine there is none, which is what M-206 exists to say out
# loud instead of publishing the score anyway.
readonly CASE_TIMEOUT=${S3TESTS_CASE_TIMEOUT:-60}
# Layer two of the access model, and off here because it is off in the shipped server (M28). The
# number this harness prints is the number of the configuration people get, so turning it on
# quietly would publish a score for a server nobody runs. `S3TESTS_ANONYMOUS=1` measures the other
# configuration on purpose, and what it is worth is written beside it in docs/s3-tests.md.
readonly ANONYMOUS=${S3TESTS_ANONYMOUS:-0}

# Where the server under test is. Empty — which is CI and every run before M30 — means the one this
# script starts itself on the loopback. `host:port` scores a **deployment** instead, and the number
# then includes everything standing between the suite and the server (M-212).
#
# That is the whole point of the switch rather than a convenience: a proxy that rewrites a header,
# caps a body or cuts a slow upload changes this score, and nothing else in this repository would
# notice. `ETag` replaced by nginx already cost five green tests once.
#
# Two things the harness cannot do for a server it did not start, and both are the operator's:
#   * `BOCHKA_LIFECYCLE_DAY_SECONDS` on that server has to equal S3TESTS_LC_DAY here, or the
#     lifecycle family measures nothing while looking like it passed;
#   * the three keys below have to exist there. A remote run against a store with other keys is a
#     few hundred fixture errors, not a low score.
readonly ENDPOINT=${BOCHKA_S3TESTS_ENDPOINT:-}
# `1` makes the suite speak https and keeps certificate verification off — a stand's certificate is
# usually its own, and this run is about what the proxy does to the requests, not about PKI.
readonly ENDPOINT_TLS=${BOCHKA_S3TESTS_TLS:-0}

if [ -n "$ENDPOINT" ]; then
  case "$ENDPOINT" in
    *:*) ;;
    *) echo "BOCHKA_S3TESTS_ENDPOINT must be host:port, not \"$ENDPOINT\"" >&2; exit 3 ;;
  esac
  HOST=${ENDPOINT%:*}
  TARGET_PORT=${ENDPOINT##*:}
else
  HOST=127.0.0.1
  TARGET_PORT=$PORT
fi
readonly HOST TARGET_PORT
readonly IS_SECURE=$([ "$ENDPOINT_TLS" = 1 ] && echo True || echo False)
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
# Set when a case ran out of clock (M-206). Read by the exit trap rather than acted on where it is
# discovered, so that the refusal to call the run a measurement cannot be skipped.
timedout=no
# Set when the classification and the run disagree in either direction: a case a **closed** task
# claims to have fixed is still red (M-260), or a case marked `deferred` has started passing
# (M-261). Same shape as the flag above and for the same reason.
regressed=no

# Where the evidence goes when it is kept. Inside `build/` because that is already ignored, and
# under a fixed name because the point is that the next command can find it without being told.
readonly EVIDENCE="$root/build/s3-tests"

# Cleared before the run, not after it, and this is a fix rather than tidiness. Evidence is kept
# only when something asks for it — errors, an unclassified failure, S3TESTS_KEEP_LOG — so a run
# that classifies cleanly leaves whatever the **previous** run put here, with nothing to say it is
# old. That directory then reads as the current answer: three hours of a stale `results.xml` were
# once diagnosed as a regression on the wire, because the messages in it came from a build that
# predated the feature being measured. An absent directory says "nothing was kept"; a stale one
# lies.
rm -rf "$EVIDENCE"

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
  if [ -n "$ENDPOINT" ]; then
    # There is no server log to keep for a server this script did not start, and saying
    # otherwise sends the next reader looking for a file that was never written.
    echo "kept the raw output in $EVIDENCE; the server log is on the deployment" >&2
  else
    echo "kept the server log and the raw output in $EVIDENCE" >&2
  fi
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
  # And the other way a run can produce a number that is not one (M-206). It lives here beside its
  # sibling, in the trap, for the reason the sibling is here: a check placed in the body only fires
  # when control reaches it, and this script has already once ended without printing either a score
  # or a complaint. A guard that can be stepped over is not a guard.
  if [ "${timedout:-no}" = yes ]; then
    echo "this run measured the machine as much as the server: see the timed-out cases above" >&2
    [ "$status" -eq 0 ] && status=1
  fi
  # The translation of M13's rule into the gate. A milestone used to be closed because the total
  # moved, while the cases it named stayed red; the total is not consulted here at all, one named
  # case is.
  if [ "${regressed:-no}" = yes ]; then
    echo "the classification and the run disagree: see closed-and-failing / deferred-but-passing" >&2
    [ "$status" -eq 0 ] && status=1
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

if [ -z "$ENDPOINT" ]; then
  "$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

  # `BOCHKA_LOG=1` is on for the whole run, and it is the half of this that matters: a preserved log
  # with nothing in it but the startup banner answers no question at all. It costs a few megabytes of
  # a file that is thrown away on a green run.
  # `BOCHKA_LIFECYCLE_DAY_SECONDS` and the suite's `lc_debug_interval` below are one setting written
  # twice, and they must agree. A lifecycle rule counts days; a test that waited one would never be
  # run by anybody, so the suite shortens the day and the server has to shorten it the same way —
  # exactly like `api_name`, which is already set from both ends a few lines down.
  #
  # Five and not the suite's default of ten, and that was arithmetic rather than taste: the longest
  # case sleeps ten intervals (`test_lifecycle_expiration_size_gt`), and ten times ten is over the
  # sixty-second per-test timeout. At five it is fifty, and the timeout still measures a hang.
  BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_DATA_DIR="$work/data" BOCHKA_LOG=1 \
    BOCHKA_LIFECYCLE_DAY_SECONDS=$LC_DAY \
    BOCHKA_ANONYMOUS=$ANONYMOUS \
    BOCHKA_KEYS="s3main:s3mainsecret,s3alt:s3altsecret,s3tenant:s3tenantsecret" \
    "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!
else
  echo "scoring the deployment at $HOST:$TARGET_PORT rather than a server this script starts"
  [ "$ANONYMOUS" = 1 ] && echo "  S3TESTS_ANONYMOUS=1 is set here, but the deployment decides its own"
  echo "  a lifecycle day is $LC_DAY s here; the same number has to be set on that server"
fi

for _ in $(seq 1 50); do
  (exec 3<>/dev/tcp/"$HOST"/"$TARGET_PORT") 2>/dev/null && break
  sleep 0.2
done
if ! (exec 3<>/dev/tcp/"$HOST"/"$TARGET_PORT") 2>/dev/null; then
  echo "nothing is answering on $HOST:$TARGET_PORT" >&2
  [ -z "$ENDPOINT" ] && cat "$log" >&2
  exit 3
fi

# The configuration is built from the suite's own sample rather than written by hand: its loader
# insists on sections that have nothing to do with S3 — `iam`, `webidentity` — and fails collection
# of the whole file if one is missing. Patching the sample keeps that list the suite's problem.
cat > "$work/make-conf.py" <<'PYCONF'
import configparser, sys
cfg = configparser.RawConfigParser()
cfg.read("/s3-tests/s3tests.conf.SAMPLE")
cfg.set("DEFAULT", "host", sys.argv[1])
cfg.set("DEFAULT", "port", sys.argv[2])
cfg.set("DEFAULT", "is_secure", sys.argv[3])
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
    # And the display name, for the same reason and out of the same fact: this server's users are
    # its access keys, so what it can put in a `DisplayName` is the key. The sample's `M. Tester`
    # is ceph's own fixture data, and leaving it in would have the suite comparing an ACL document
    # against a name nothing here has ever heard of (M27).
    cfg.set(section, "display_name", key)
    # The suite asks the server for a bucket's location and compares it with this. It is the name
    # of the region the deployment runs in, not a constant, so it has to agree with BOCHKA_REGION.
    # Set per section rather than in DEFAULT: the sample sets it inside the sections, and a value
    # there wins over the default, so a DEFAULT-only version of this line changed nothing.
    cfg.set(section, "api_name", "us-east-1")
# The other half of BOCHKA_LIFECYCLE_DAY_SECONDS. The suite reads it from `s3 main` only
# (`s3tests/functional/__init__.py:248`) and defaults it to ten, so leaving it out here would have
# the tests sleeping for ten-second days against a server whose day is five — which reads as
# "lifecycle works" while proving nothing, because everything is over-due by the time it looks.
cfg.set("s3 main", "lc_debug_interval", sys.argv[4])
with open("/work/s3tests.conf", "w") as out:
    cfg.write(out)
PYCONF

# The guard that decides whether this run counts, asked first whether it still works (M-206). What
# can silently break it is not our code but the wording `pytest-timeout` puts into a failure
# message, and a guard that has quietly stopped recognising its subject looks exactly like a run
# with no timeouts in it.
if ! python3 "$root/ci/s3_tests_health.py" --self-test; then
  echo "refusing to score a run whose timeout guard does not answer" >&2
  exit 3
fi

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
  python /work/make-conf.py '"$HOST"' '"$TARGET_PORT"' '"$IS_SECURE"' '"$LC_DAY"'
  S3TEST_CONF=/work/s3tests.conf timeout 5400 python -m pytest s3tests/functional/test_s3.py \
    -p no:cacheprovider -q --no-header -rN --continue-on-collection-errors \
    --timeout='"$CASE_TIMEOUT"' --timeout-method=signal --junit-xml=/work/results.xml \
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
# Rounded, not truncated: this line is the source of the number quoted in README.md, docs/README.md,
# docs/s3-tests.md and CLAUDE.md, and the two conventions first disagreed at 431 of 744 — 57.93%,
# which truncates to 57 and rounds to 58. A harness that prints a different percentage than the docs
# it feeds turns every reader into a bug reporter.
printf 'ceph/s3-tests: %d of %d passed (%d%%), %d failed, %d errored\n' \
  "$passed" "$ran" $(((passed * 100 + ran / 2) / ran)) "$failed" "$errors"
scored=yes
# An errored case is not a failing case: it never reached its own assertions, which almost always
# means a fixture was refused something. That is the run whose log is worth keeping.
if [ "$errors" -gt 0 ]; then keep=yes; fi
echo "the count of tests that ran is part of the number: a rising score and a shrinking suite look the same"

# What the machine was doing while it measured, and how close the slowest case came to the clock
# (M-206). Printed beside the score rather than in the log, because the number and the conditions
# it was taken under are one fact: a loaded host once turned 426 into 228 with 176 unclassified
# failures, and the score alone said "regression" for an hour.
if [ -r /proc/loadavg ]; then
  echo "the machine's load while measuring: $(cut -d' ' -f1-3 /proc/loadavg) (1, 5, 15 minutes)"
elif command -v uptime >/dev/null 2>&1; then
  echo "the machine while measuring: $(uptime)"
fi
if [ -f "$work/results.xml" ]; then
  # The one failure this script refuses to score. Everything else it classifies and publishes; a
  # run that ran out of clock is not a measurement of the server at all, and `timedout=yes` is read
  # by the exit trap, where a guard cannot be stepped over by an early `exit`.
  if ! python3 "$root/ci/s3_tests_health.py" "$work/results.xml" "$CASE_TIMEOUT"; then
    timedout=yes
    keep=yes
  fi
fi

# Why the rest fail, grouped. A bare percentage says how far there is to go and nothing about what
# the distance is made of — and the difference between "does not have versioning" and "has a defect"
# is the whole of it (M-67, Risk 2). The rules live in ci/s3-tests-scope.txt so that a failure
# nobody has classified shows up as `unclassified` rather than quietly joining a bucket.
if [ -f "$work/results.xml" ]; then
  echo
  classification=$(python3 "$root/ci/s3_tests_scope.py" "$work/results.xml" "$root/ci/s3-tests-scope.txt" \
    "${BOCHKA_FAILED_OUT:-}" "${BOCHKA_BACKLOG:-$root/BACKLOG.md}")
  echo "$classification"
  # A failure nobody has classified is the other kind of run worth keeping: either the rules have
  # gone stale or something new broke, and both are answered from the log rather than from here.
  if echo "$classification" | grep "unclassified" >/dev/null; then keep=yes; fi
  # A `defect` rule that names no task (M-295). Kept for the same reason as an unclassified
  # failure: both are claims nobody can follow up, and both are invisible in the score.
  if echo "$classification" | grep "unattributed" >/dev/null; then keep=yes; fi
  # A case named by a closed task and still red (M-260). Recorded here and refused in the exit
  # trap, beside the other two guards and for their reason: a check that only fires when control
  # reaches it does not fire on the runs that matter.
  if echo "$classification" | grep "closed-and-failing" >/dev/null; then regressed=yes; keep=yes; fi
  # And the other direction (M-261): a `deferred` case that passes. The exclusion list is then
  # hiding finished work and the score is understated by exactly that many, with nothing saying so.
  if echo "$classification" | grep "deferred-but-passing" >/dev/null; then regressed=yes; keep=yes; fi
  # M-218. Against a deployment the lifecycle family is measurable only if that deployment's day
  # was shortened to the same number this run uses, and there is one way to do that — the chart's
  # `lifecycleDaySeconds`. Without it those cases fail on the clock and land in `unclassified`,
  # where the wording is "nobody has looked at this one". Somebody has; say so here rather than
  # leaving it to whoever compares two scores and wonders where thirteen cases went.
  if [ -n "$ENDPOINT" ] && echo "$classification" | grep "test_lifecycle" >/dev/null; then
    echo
    echo "the lifecycle cases above fail on the clock, not on the server: this run shortens a day"
    echo "to ${LC_DAY}s and the deployment at $HOST:$TARGET_PORT almost certainly leaves it at 86400."
    echo "Install with lifecycleDaySeconds=$LC_DAY (and lifecycleDayShortenedFor naming why) to score them."
  fi
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
