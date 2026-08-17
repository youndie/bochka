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

cleanup() {
  [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; the suite cannot run" >&2; exit 3; }

"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_DATA_DIR="$work/data" \
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
with open("/work/s3tests.conf", "w") as out:
    cfg.write(out)
PYCONF

echo "running ceph/s3-tests (this pulls the suite and takes a few minutes)"
docker run --rm --network host -v "$work:/work" "$IMAGE" bash -c '
  set -e
  apt-get update -qq >/dev/null 2>&1 && apt-get install -qq -y git >/dev/null 2>&1
  git clone -q --depth 1 https://github.com/ceph/s3-tests.git /s3-tests
  cd /s3-tests
  # pytest-timeout on top of the requirements the suite declares. A test that waits on something
  # bochka never answers otherwise burns a socket timeout, and a few dozen of those eat the whole
  # budget before pytest can print a summary — the one thing this script exists to produce.
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
    -p no:cacheprovider -q --no-header -rN --tb=no --continue-on-collection-errors \
    --timeout=20 --timeout-method=signal \
    > /work/pytest.out 2>&1 || true
  tail -5 /work/pytest.out
' 2>&1 | tail -6

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
echo "the count of tests that ran is part of the number: a rising score and a shrinking suite look the same"
