#!/usr/bin/env bash
# Points s3kn — an S3 client written from the specification, in another language runtime — at
# bochka.
#
# It is the second-weakest check in this repository and it is here anyway. Weakest, because it
# shares an author with the server and signs its bodies the one simple way: a green run against it
# proves nothing about the streaming paths (research, Р6). Worth having, because it is an
# independent implementation of the signature, and because it is the only client to hand that
# exercises **presigned URLs**, which no other harness here touches.
#
# So it is a client, never the metric. The metric is ci/s3-tests.sh.
#
# The checkout is expected next door; `S3KN_DIR` overrides. Absent means SKIPPED, not passed.
set -uo pipefail

readonly PORT=${BOCHKA_PORT:-19002}
readonly ENDPOINT="http://127.0.0.1:${PORT}"
readonly KEY=s3kn-test-access-key
readonly SECRET=s3kn-test-secret-key

root=$(cd "$(dirname "$0")/.." && pwd)
s3kn=${S3KN_DIR:-$root/../s3kn}
work=$(mktemp -d)
log="$work/bochka.log"

cleanup() {
  [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
}
trap cleanup EXIT

if [ ! -f "$s3kn/settings.gradle.kts" ]; then
  echo "SKIPPED: no s3kn checkout at $s3kn (set S3KN_DIR)" >&2
  exit 0
fi
command -v docker >/dev/null || { echo "docker is needed to create the buckets" >&2; exit 3; }

"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

# s3kn has no CreateBucket — it is outside its v1 — so the buckets its tests expect are made from
# outside, exactly as its own docker-compose does with `mc`.
BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_DATA_DIR="$work/data" \
  BOCHKA_KEYS="$KEY:$SECRET" \
  "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!

for _ in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.2
done
(exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null || { echo "bochka did not come up" >&2; cat "$log" >&2; exit 3; }

for bucket in s3kn-test s3kn-test-other; do
  docker run --rm --network host \
    -e AWS_ACCESS_KEY_ID=$KEY -e AWS_SECRET_ACCESS_KEY=$SECRET -e AWS_DEFAULT_REGION=us-east-1 \
    -e AWS_EC2_METADATA_DISABLED=true --entrypoint "" amazon/aws-cli:latest \
    aws --endpoint-url "$ENDPOINT" s3 mb "s3://$bucket" >/dev/null 2>&1
done

echo "running s3kn's live tests against bochka"
# The JVM target rather than linuxX64: the same test code and the same assertions, without waiting
# for a Kotlin/Native toolchain to download. What is being checked is bochka, not which engine
# s3kn was compiled against.
(
  cd "$s3kn" && \
  S3_E2E_ENDPOINT="$ENDPOINT" S3_E2E_ACCESS_KEY="$KEY" S3_E2E_SECRET_KEY="$SECRET" \
  S3_E2E_REQUIRED=1 \
  ./gradlew --console=plain -q :s3-client:jvmTest --tests '*E2e*' --tests '*E2E*'
) > "$work/s3kn.out" 2>&1
status=$?

results=$(find "$s3kn/s3-client/build/test-results/jvmTest" -name '*E2e*.xml' 2>/dev/null)
if [ -z "$results" ]; then
  echo "s3kn produced no test results at all; that is a failure, not a pass" >&2
  tail -30 "$work/s3kn.out" >&2
  exit 1
fi

# Parsed with python: the counts and the names of what failed both come out of the XML, and a
# harness that prints a number without saying which checks it lost is a number nobody can act on.
python3 - "$s3kn" <<'PARSE'
import glob, sys, xml.etree.ElementTree as ET

files = glob.glob(sys.argv[1] + "/s3-client/build/test-results/jvmTest/*E2e*.xml")
total = failed = 0
names = []
presign_ok = None
for path in files:
    for case in ET.parse(path).getroot().iter("testcase"):
        total += 1
        bad = case.find("failure")
        if bad is None:
            bad = case.find("error")
        name = case.get("name", "")
        if "presigned get" in name:
            presign_ok = bad is None
        if bad is not None:
            failed += 1
            first = (bad.get("message") or "").split(chr(10))[0]
            names.append((name, first[:120]))

# One of s3kn's expectations is about MinIO rather than about S3, and it is named here for the
# same reason ceph/s3-tests failures are classified in ci/s3-tests-scope.txt: a failure nobody has
# explained and a failure somebody decided about look identical in a count. M-182 decided this one.
EXPLAINED = {
    "is answered with 411 when a body arrives without a stated length":
        "s3kn asserts MinIO's answer (its e2e runs against MinIO, docker-compose.yml). "
        "ceph/s3-tests:1597 sends the same shape -- botocore drops Content-Length entirely when "
        "Transfer-Encoding is added -- and expects 200, unmarked for AWS. bochka answers 200 (M-182).",
}

print()
print("s3kn: %d of %d passed" % (total - failed, total))
explained = [n for n, _ in names if n in EXPLAINED]
if explained:
    print("  %d of the failures are decided rather than open:" % len(explained))
    for name in sorted(explained):
        print("    %s" % name)
        print("      %s" % EXPLAINED[name])
for name, why in sorted(names):
    if name in EXPLAINED:
        continue
    print("  failed: %s" % name)
    print("          %s" % why)
print()
if presign_ok:
    print("  presigned GET: served — the one thing no other harness here checks")
else:
    print("  presigned GET: NOT served", file=sys.stderr)
    sys.exit(1)
# Every failure left is meant to be a feature that has not been built yet, and the milestone it
# belongs to is in BACKLOG.md. If one of them is something that *is* built, this script has just
# found a defect and the count above is the place it shows.
sys.exit(0)
PARSE
status_parse=$?

[ "$status" -eq 0 ] || echo "(gradle exited $status: the failures above are the reason)"
exit $status_parse
