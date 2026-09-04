#!/usr/bin/env bash
# Uses what was published, the way somebody who did not build it would.
#
# A publish job can tell you it published. It cannot tell you the result is usable: wrong
# coordinates, a POM that names a dependency nobody pushed, an image whose entrypoint is broken —
# every one of those looks like a successful publish from the publishing side, and like nothing at
# all until the first consumer arrives.
#
# So this pulls the image by name and points a client at it, and resolves the jar from the
# repository into a project that has never seen this source tree. Nothing here reads
# `build/`; that is the point.
#
#   BOCHKA_IMAGE=ghcr.io/youndie/bochka:v0.1.0 ./ci/consume-published.sh
set -uo pipefail

readonly IMAGE=${BOCHKA_IMAGE:?set BOCHKA_IMAGE to the published image}
readonly VERSION=${BOCHKA_VERSION:-${IMAGE##*:}}
readonly PORT=${BOCHKA_PORT:-19003}
# Where the jar is resolved from. `central` means Maven Central and NOTHING ELSE, which is the only
# way this script can say anything about a Central release: the consumer project below lists
# `mavenCentral()` unconditionally, so with a second repository beside it a green run proves the
# artefact exists in one of the two and cannot say which. That is the shape of check that passes
# for the wrong reason -- and the reason to have a mode with one repository in it.
readonly REPO=${BOCHKA_REPO_URL:-https://reposilite.kotlin.website/snapshots}
readonly NAME=bochka-consume

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)

# The Kotlin version is read out of the version catalogue rather than written here. bochka emits
# JVM 25 bytecode, and a consumer on an older Kotlin cannot target 25 — the build then fails with
# "compileJava (25) and compileKotlin (24)", which reads as a bug in the consumer's project and is
# really a version that cannot compile against this artefact at all. Hardcoding a version here
# made this script assert something about a Kotlin nobody uses.
readonly KOTLIN=${BOCHKA_KOTLIN:-$(sed -n 's/^kotlin *= *"\(.*\)"/\1/p' "$root/gradle/libs.versions.toml" | head -1)}
# Empty when the answer has to come from Central, so that the consumer has exactly one place to
# find the artefact and a resolution failure means what it says.
if [ "$REPO" = central ]; then
  EXTRA_REPO=""
  echo "resolving from Maven Central only: nothing else is on the consumer's repository list"
else
  EXTRA_REPO="    maven { url = uri(\"$REPO\") }"
fi

passed=0; failed=0
pass() { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail() { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
}
trap cleanup EXIT

# The consumer project has no Gradle wrapper on purpose — a wrapper would be this repository's
# Gradle, and the point is to be somebody else. So a system `gradle` is required, and its absence
# has to say so rather than surface as "the published jar does not resolve".
command -v gradle >/dev/null || { echo "gradle is not on PATH; this script needs a system Gradle" >&2; exit 3; }

echo "pulling $IMAGE"
docker pull -q "$IMAGE" >/dev/null 2>&1 && pass "the image can be pulled by name" || {
  fail "the image cannot be pulled by name"
  printf 'passed %d, failed %d\n' "$passed" "$failed"; exit 1
}

docker run -d --name "$NAME" -p "$PORT:9000" -e BOCHKA_KEYS=consumekey:consumesecret "$IMAGE" >/dev/null 2>&1
for _ in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 0.5; done

if docker run --rm --network host \
     -e AWS_ACCESS_KEY_ID=consumekey -e AWS_SECRET_ACCESS_KEY=consumesecret \
     -e AWS_DEFAULT_REGION=us-east-1 -e AWS_EC2_METADATA_DISABLED=true \
     -v "$work:/work" --entrypoint "" amazon/aws-cli:latest sh -c "
       echo published > /work/probe.txt &&
       aws --endpoint-url http://127.0.0.1:$PORT s3 mb s3://consume &&
       aws --endpoint-url http://127.0.0.1:$PORT s3 cp /work/probe.txt s3://consume/probe.txt &&
       aws --endpoint-url http://127.0.0.1:$PORT s3 cp s3://consume/probe.txt /work/back.txt" >/dev/null 2>&1 &&
   [ "$(cat "$work/back.txt" 2>/dev/null)" = "published" ]; then
  pass "aws-cli round-trips an object through the published image"
else
  fail "the published image does not serve a round trip"
fi

# --- the jar, resolved from the repository into a project that has never seen this source -------
mkdir -p "$work/consumer/src/main/kotlin"
cat > "$work/consumer/settings.gradle.kts" <<EOF
// The toolchain resolver, and it is not boilerplate: bochka is compiled to JVM 25 bytecode, so a
// consumer has to be able to target 25 too. Without this plugin Gradle cannot provision a JDK it
// does not already have, Kotlin quietly falls back to its own highest target, and the build fails
// with "compileJava (25) and compileKotlin (24)" — which reads as a bug in the consumer's project
// and is really a missing line. It is in bochka's own settings.gradle.kts for the same reason.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "consumer"
dependencyResolutionManagement {
  repositories {
    mavenCentral()
$EXTRA_REPO
  }
}
EOF
cat > "$work/consumer/build.gradle.kts" <<EOF
plugins {
    kotlin("jvm") version "$KOTLIN"
    application
}
kotlin { jvmToolchain(25) }
dependencies { implementation("io.github.youndie.bochka:bochka-embedded:$VERSION") }
application { mainClass.set("ConsumerKt") }
EOF
cat > "$work/consumer/src/main/kotlin/Consumer.kt" <<'EOF'
// Compiles against the published ABI and runs it, which is the half a `publish` task cannot check
// about itself: a jar that resolves and does not compile is still a failed publication, and one
// that compiles against a POM naming modules nobody pushed fails only at run time.
import io.github.youndie.bochka.embedded.Bochka

fun main() {
    Bochka.start().use { bochka ->
        check(bochka.port > 0) { "the embedded server did not bind" }
        println("embedded bochka answered on ${bochka.endpoint}")
    }
}
EOF

if (cd "$work/consumer" && gradle --no-daemon -q run >/dev/null 2>&1); then
  pass "a project that has never seen this source compiles and runs against the published jar"
else
  fail "the published jar does not resolve, does not compile, or does not run"
  (cd "$work/consumer" && gradle --no-daemon run 2>&1 | tail -15)
fi

echo
printf 'passed %d, failed %d\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
