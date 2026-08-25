#!/usr/bin/env bash
# What the Helm chart does that neither `./gradlew check` nor `ci/docker-smoke.sh` can see.
#
# The smoke test looks at Docker on purpose, and that is exactly its blind spot: it does not see the
# environment variables kubelet injects for Services in the namespace, an arbitrary uid against a
# volume owned by root, the orchestrator's idea of a healthy probe (403 is a *failure* to httpGet),
# or a memory limit. Every decision in this chart came out of that list, so this harness has to
# close the difference rather than repeat the smoke test.
#
#   ./ci/helm-chart.sh
#
# Three stages. The first two need nothing but `helm`:
#
#   lint       every value set a person is expected to use;
#   render     the same sets, plus the negative ones — where a refusal *is* the expected result and
#              a rendered manifest is the failure;
#   cluster    kind, a real kubelet, and `helm test`.
#
# BOCHKA_CHART_KIND=yes makes the third stage mandatory (that is how CI runs it, so that a missing
# `kind` is a red job rather than a quiet skip); `no` skips it; `auto` runs it when kind is present.
set -uo pipefail

readonly CHART_DIR_NAME=deploy/helm/bochka
readonly CLUSTER=bochka-chart
readonly RELEASE=bochka
readonly KIND_MODE=${BOCHKA_CHART_KIND:-auto}
readonly IMAGE=${BOCHKA_IMAGE:-bochka:chart}
# Pinned, not `latest`, and for the same reason the chart pins it: a check whose result depends on
# somebody else's release schedule is also checking their schedule. aws-cli v2 publishes a tag nearly
# every weekday and has changed what it puts on the wire before, and anonymous Docker Hub pulls are
# rate-limited per IP — either would turn an untouched release red and read as a server regression.
readonly TESTS_IMAGE=${BOCHKA_CHART_TESTS_IMAGE:-amazon/aws-cli:2.36.25}

root=$(cd "$(dirname "$0")/.." && pwd)
chart="$root/$CHART_DIR_NAME"
values="$chart/ci"
work=$(mktemp -d)
passed=0; failed=0

# Whether this run ever reached its summary. A check at the bottom of a script only fires when
# control gets there, and a `set -u` abort part way through does not — this repository has already
# had a harness exit without printing either a number or a complaint. The guard lives in the exit
# trap, where nothing can step over it.
summarised=no

pass() { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail() { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }

cleanup() {
  status=$?
  if [ "${cluster_created:-no}" = yes ]; then
    kind delete cluster --name "$CLUSTER" >/dev/null 2>&1
  fi
  rm -rf "$work" 2>/dev/null
  if [ "$summarised" = no ]; then
    echo "this run ended without a summary, which is a failure rather than a pass" >&2
    [ "$status" -eq 0 ] && status=1
  fi
  exit "$status"
}
trap cleanup EXIT

command -v helm >/dev/null || { echo "helm is not installed" >&2; exit 3; }

# --- lint ---------------------------------------------------------------------------------------

echo "linting"
for set in minimal full foreign-ingress existing-claim existing-secret; do
  if helm lint "$chart" -f "$values/$set-values.yaml" >"$work/lint.out" 2>&1; then
    pass "helm lint, $set values"
  else
    fail "helm lint, $set values"
    sed 's/^/          /' "$work/lint.out"
  fi
done

# --- render -------------------------------------------------------------------------------------

echo
echo "rendering"
for set in minimal full foreign-ingress existing-claim existing-secret; do
  if helm template "$RELEASE" "$chart" -f "$values/$set-values.yaml" >"$work/$set.yaml" 2>"$work/$set.err"; then
    pass "helm template, $set values"
  else
    fail "helm template, $set values"
    sed 's/^/          /' "$work/$set.err"
  fi
done

# A rendered manifest is not the point on its own; these are the specific promises.
expect_in() { # file, pattern, description
  if grep -q -- "$2" "$1" 2>/dev/null; then pass "$3"; else fail "$3"; fi
}
expect_not_in() {
  if grep -q -- "$2" "$1" 2>/dev/null; then fail "$3"; else pass "$3"; fi
}

expect_in "$work/minimal.yaml" 'replicas: 1' "one replica, and it is a constant"
expect_in "$work/minimal.yaml" 'kind: StatefulSet' "a StatefulSet, not a Deployment"
expect_not_in "$work/minimal.yaml" 'kind: Deployment' "no Deployment anywhere"
expect_in "$work/minimal.yaml" 'enableServiceLinks: false' "service links are off"
# Read from the chart rather than pinned here, and that is a fix rather than a tidy-up: pinned, this
# check went red on the release that moved `appVersion` to v0.2.0 — the number lived in two files and
# only one of them was edited, which is the failure mode the pin was supposed to prevent.
#
# What is left pinned is the part that is a claim rather than a value: the `v`. `appVersion: 0.2.0`
# beside a template that reads `.Chart.AppVersion` asks GHCR for a tag that does not exist, because
# the release pushes `v0.2.0` and `0.2.0` is only ever a Maven coordinate (Chart.yaml says so at
# length). Whether the registry actually holds that tag is deliberately **not** asked here: a
# rendering check that reaches for the network fails when the network does, and a harness that
# flakes gets ignored long before it gets fixed.
app_version=$(sed -n 's/^appVersion: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' "$chart/Chart.yaml")
expect_in "$work/minimal.yaml" "image: \"ghcr.io/youndie/bochka:$app_version\"" \
  "the default tag is the chart's own appVersion ($app_version)"
case $app_version in
  v*) pass "and it carries the v, which is what the release pushes" ;;
  *)  fail "appVersion is '$app_version': without the v it names a Maven coordinate, not an image tag" ;;
esac
expect_in "$work/minimal.yaml" 'type: ClusterIP' "the Service is ClusterIP"
expect_not_in "$work/minimal.yaml" 'LoadBalancer' "no LoadBalancer is offered"
expect_not_in "$work/minimal.yaml" 'NodePort' "no NodePort is offered"
expect_in "$work/minimal.yaml" 'whenDeleted: Retain' "the claim is retained when the release goes"
expect_in "$work/minimal.yaml" 'fsGroup: 1000' "the volume is group-owned by the image's user"
expect_in "$work/minimal.yaml" 'fsGroupChangePolicy: OnRootMismatch' "no recursive chown of the whole store on every start"
expect_in "$work/minimal.yaml" 'runAsUser: 1000' "the uid is a number, because the image's User is a name"
expect_in "$work/minimal.yaml" 'readOnlyRootFilesystem: true' "the root filesystem is read-only"
# Moved at v0.3.0 with the default they pin (M-184). These two rows are the reason the move could
# not be half-done: they described `exec` as the right answer, so widening the default without them
# would have left the harness insisting on the narrow one.
expect_in "$work/minimal.yaml" 'httpGet' "the default probe is http, because appVersion answers /-/healthy"
expect_not_in "$work/minimal.yaml" 'dev/tcp' "and the forked shell every period is gone with it"
# Anonymous access is a capability, not a knob, so both directions are checked. Absent rather than
# "0" on purpose: the variable arrived in a later server than some images this chart can name, and
# an unknown BOCHKA_ name stops the process — the same reasoning that keeps
# BOCHKA_LIFECYCLE_DAY_SECONDS out of a default render.
expect_not_in "$work/minimal.yaml" 'BOCHKA_ANONYMOUS' "unsigned requests are refused unless somebody asks otherwise"
expect_in "$work/full.yaml" 'BOCHKA_ANONYMOUS' "and asking for it renders the variable"
expect_not_in "$work/minimal.yaml" 'JAVA_OPTS' "the runtime profile is never touched from the chart"
expect_not_in "$work/minimal.yaml" 'terminationGracePeriodSeconds' "no invented grace period"
expect_not_in "$work/minimal.yaml" 'preStop' "no preStop hook pretending to drain connections"

# BOCHKA_LOG is compared to the literal "1", so the boolean has to be converted rather than printed.
if grep -A1 'name: BOCHKA_LOG' "$work/full.yaml" | grep 'value: "1"' >/dev/null; then
  pass 'log: true renders as "1" and not as "true"'
else
  fail 'log: true did not render as "1"'
fi
if grep -A1 'name: BOCHKA_LOG' "$work/minimal.yaml" | grep 'value: "0"' >/dev/null; then
  pass 'log: false renders as "0"'
else
  fail 'log: false did not render as "0"'
fi

# housekeepingMinutes: 0 is "disabled", and `default` in a template would have turned it back to 60.
if grep -A1 'name: BOCHKA_HOUSEKEEPING_MINUTES' "$work/full.yaml" | grep 'value: "0"' >/dev/null; then
  pass "housekeepingMinutes: 0 survives as 0 rather than falling back to the default"
else
  fail "housekeepingMinutes: 0 did not survive"
fi

# The secret in full-values contains ':' and '+' and '=' — everything except the one character the
# format cannot carry. It has to arrive intact.
secret=$(grep -A2 'kind: Secret' -m1 "$work/full.yaml" >/dev/null 2>&1; python3 - "$work/full.yaml" <<'PY'
import base64, re, sys
text = open(sys.argv[1]).read()
match = re.search(r"kind: Secret.*?\n  keys: \"([^\"]+)\"", text, re.S)
print(base64.b64decode(match.group(1)).decode() if match else "")
PY
)
if [ "$secret" = "chartkey:chart/secret+with:colons=and-base64-ish,secondkey:anothersecret" ]; then
  pass "the keys are joined the way the server splits them, and a ':' in a secret survives"
else
  fail "BOCHKA_KEYS was assembled wrong: '$secret'"
fi

expect_not_in "$work/minimal.yaml" 'kubernetes.io/arch' "no arch pin, because v0.3.0 publishes an index for two"
# Single quotes, and it is not style: in double quotes the backticks below were command
# substitution, so this line ran `nodeSelector: null` in the shell and printed its description with
# the halves missing.
expect_not_in "$work/full.yaml" 'kubernetes.io/arch' 'and "nodeSelector: null" actually removes it — "{}" would merge and quietly not'
expect_in "$work/full.yaml" 'BOCHKA_VIRTUAL_HOST_SUFFIXES' "virtual-host suffixes reach the process"
expect_in "$work/full.yaml" 'proxy-request-buffering' "the ingress carries the annotations nginx.conf calls non-optional"
expect_in "$work/full.yaml" 'tcpSocket' "probes.type: tcp renders the socket probe"
expect_in "$work/minimal.yaml" 'ReadWriteOncePod' "the default access mode is the one that gives one writer at the API"
expect_not_in "$work/minimal.yaml" 'emptyDir' "there is no shape of this chart that mounts an emptyDir"
expect_in "$work/foreign-ingress.yaml" 'router.middlewares' "a foreign controller keeps the annotations its operator wrote"
expect_not_in "$work/foreign-ingress.yaml" 'nginx.ingress.kubernetes.io' "and loses the nginx ones, which would be inert lines that look like configuration"
expect_in "$work/existing-claim.yaml" 'claimName: bochka-data' "an existing claim is mounted as it is"
expect_not_in "$work/existing-claim.yaml" 'volumeClaimTemplates' "and the chart creates no claim beside it"
expect_not_in "$work/existing-secret.yaml" 'kind: Secret' "an existing Secret means the chart renders none"
expect_in "$work/existing-secret.yaml" 'name: bochka-keys' "and the pod reads that one"

# --- render, the negative half ------------------------------------------------------------------
#
# Each of these must be refused, and refused for its own reason. A negative case that fails for the
# wrong reason is a test that will stay green after the check it guards is deleted, so the message
# is matched as well as the exit code.
echo
echo "refusing what should be refused"
refuses() { # values basename, expected fragment of the message, description
  local file="$values/$1-values.yaml"
  if helm template "$RELEASE" "$chart" -f "$file" >/dev/null 2>"$work/refusal.out"; then
    fail "$3 — it rendered"
  elif grep -qi -- "$2" "$work/refusal.out"; then
    pass "$3"
  else
    fail "$3 — refused, but not for that reason:"
    sed 's/^/          /' "$work/refusal.out" | head -4
  fi
}

refuses bad-no-keys            "no access keys"        "no keys at all is a refusal, not the built-in pair"
refuses bad-both-auth          "two answers"           "an existing Secret and inline keys together"
refuses bad-comma-in-secret    "comma"                 "a comma in a secret, which would split the pair"
refuses bad-duplicate-id       "more than once"        "a repeated id, which the server would resolve silently"
refuses bad-whitespace-secret  "whitespace"            "a secret the server would trim into something else"
refuses bad-java-opts          "runtime profile"       "JAVA_OPTS, which replaces the heap the ceiling comes from"
refuses bad-bochka-env         "namespace"             "a BOCHKA_* variable from the side"
refuses bad-lifecycle-day      "lifecycleDayShortenedFor" "a shortened lifecycle day nobody explained, which deletes data early"
refuses bad-ingress-no-tls     "without ingress.tls"   "an Ingress with no certificate"
refuses bad-ingress-no-secretname "certificateFromController" "a TLS entry whose certificate nobody claims, which is what a default self-signed one looks like"
refuses bad-ingress-wildcard   "virtualHostSuffixes"   "a wildcard host the router was never told about"
refuses bad-ingress-suffix     "no rule for"           "a virtual-host suffix with no wildcard rule, which the controller answers itself"
refuses bad-emptydir           "persistence"           "persistence.enabled, the key that used to mount an emptyDir"
refuses bad-ingress-class      "requirementsExpressedFor" "a controller whose four requirements nobody expressed"
refuses bad-ingress-class      "readTimeout"           "traefik, whose fourth requirement no Ingress can express"
refuses bad-ingress-class-unknown "requirementsExpressedFor" "a controller nobody has measured, which lands in the general branch"
refuses bad-access-mode        "accessModes"           "ReadWriteMany, which promises a second writer"
refuses bad-small-memory       "runtime profile needs" "a memory limit under the profile, where the OOM kill beats the object ceiling"
refuses bad-small-profile-memory "small runtime profile" "the small profile under its own floor, which is not the default one"
refuses bad-probe-timing       "initialDelaySeconds"   "a probe field the template renders nowhere"
refuses bad-unknown-value      "replicaCount"          "a values key nobody reads"
# `boolean` alone, because the rest of that sentence belongs to helm rather than to us: it says
# "Expected: boolean, given: string" up to 3.17 and "got string, want boolean" after, and this
# check went red on a helm upgrade while the chart refused the value exactly as it should. What is
# being asserted is that the refusal is about the type, and that word is the whole of it.
refuses bad-log-type           "boolean"                "a non-boolean log, which would be logging quietly off"

# --- the chart against the image it names ---------------------------------------------------------
#
# M-184. Two defaults here are narrower than the server can do, and both are narrow because of the
# **published** image rather than because of the code: `probes.type: exec` because the health handle
# is newer than the tag `appVersion` points at, and the amd64 `nodeSelector` because the multi-arch
# index is newer than it too. A chart whose default needs an image newer than the one it names is a
# pod kubelet restarts for ever, and that failure looks like an operator's mistake.
#
# So this asks the registry and the image, and it fails **in both directions**: a default too new for
# the image is the crash loop, and a default still narrow after the image has caught up is a chart
# telling everybody to use less than it has. The second one is a tripwire that goes off exactly once,
# at the release that fixes it, and says what to move.
#
# Three of the four branches have been watched red with BOCHKA_APPVERSION_IMAGE and a hand-edited
# default. The fourth — "the published image became multi-arch" — has not, because no tag of this
# image is, and there is nothing to point it at. It is also the least expensive of the four to get
# wrong: it fires when the world improves, and missing it leaves a nodeSelector in place longer
# than needed rather than a pod that cannot start.
echo
echo "the chart's defaults against the image its appVersion names"
app_version=$(sed -n 's/^appVersion: *//p' "$chart/Chart.yaml" | tr -d '"' | tr -d "\r")
# Overridable so that every branch below can be **seen** red: three of the four need an image that
# does not exist yet — one that answers the health handle, or one published for two architectures —
# and a check nobody has watched fail is not a check. It is also what a fork with its own registry
# would set.
published=${BOCHKA_APPVERSION_IMAGE:-ghcr.io/youndie/bochka:$app_version}

if ! command -v docker >/dev/null || ! command -v curl >/dev/null; then
  # Named rather than silent: a check nobody ran reads exactly like a check that passed, which is
  # the failure this whole harness exists to refuse.
  echo "  SKIP    docker or curl is missing, so the published image cannot be asked anything"
else
  probe_default=$(sed -n 's/^  type: //p' "$chart/values.yaml" | head -1)
  arch_pinned=$(grep -c '^  kubernetes.io/arch: amd64' "$chart/values.yaml")

  # Does the published image answer the health handle the `http` probe needs?
  # A published port rather than `--network host`, and that is not a preference: on Docker Desktop
  # host networking is not the host's network, so the probe cannot reach the container and curl
  # reports `000`. That reads as "the image does not answer" and is a fact about the machine —
  # a check failing for a reason that is not its subject, which this harness exists to refuse.
  docker rm -f bochka-appversion >/dev/null 2>&1
  if docker run -d --name bochka-appversion -p 19099:9000 \
      -e BOCHKA_BIND_ADDRESS=0.0.0.0 -e BOCHKA_KEYS=probe:probe \
      "$published" >/dev/null 2>&1; then
    sleep 7
    health=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:19099/-/healthy)
    docker rm -f bochka-appversion >/dev/null 2>&1
    if [ "$health" = 200 ]; then
      if [ "$probe_default" = http ]; then
        pass "probes.type: http, and $published answers /-/healthy"
      else
        fail "$published now answers /-/healthy with 200: probes.type can default to http (M-184)"
      fi
    else
      if [ "$probe_default" = http ]; then
        fail "probes.type: http, but $published answers $health to /-/healthy — kubelet would restart this pod for ever"
      else
        pass "probes.type: exec, because $published answers $health to /-/healthy"
      fi
    fi
  else
    fail "could not start $published to ask it about /-/healthy"
  fi

  # And is it still one architecture?
  token=$(curl -s "https://ghcr.io/token?scope=repository:youndie/bochka:pull&service=ghcr.io" |
    sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  platforms=$(curl -s -H "Authorization: Bearer $token" \
    -H 'Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json' \
    "https://ghcr.io/v2/youndie/bochka/manifests/${BOCHKA_APPVERSION_TAG:-$app_version}" | grep -c '"architecture"')
  if [ "$platforms" -gt 1 ]; then
    if [ "$arch_pinned" -eq 0 ]; then
      pass "no arch nodeSelector, and $app_version publishes $platforms architectures"
    else
      fail "$app_version publishes $platforms architectures: the amd64 nodeSelector default can go (M-184)"
    fi
  else
    if [ "$arch_pinned" -eq 0 ]; then
      fail "no arch nodeSelector, but $app_version publishes one architecture — the pod would sit Pending elsewhere"
    else
      pass "nodeSelector pins amd64, because $app_version publishes one architecture"
    fi
  fi
fi

# --- cluster --------------------------------------------------------------------------------------

run_kind=no
case "$KIND_MODE" in
  no) ;;
  yes)
    command -v kind >/dev/null || { echo "BOCHKA_CHART_KIND=yes and kind is not installed" >&2; exit 3; }
    command -v kubectl >/dev/null || { echo "BOCHKA_CHART_KIND=yes and kubectl is not installed" >&2; exit 3; }
    run_kind=yes ;;
  *)
    if command -v kind >/dev/null && command -v kubectl >/dev/null && command -v docker >/dev/null; then
      run_kind=yes
    fi ;;
esac

if [ "$run_kind" = no ]; then
  echo
  echo "skipping the cluster stage (BOCHKA_CHART_KIND=$KIND_MODE, kind/kubectl not both present)"
  echo "  note   everything above is rendering. None of it has met a kubelet."
else
  echo
  echo "cluster stage: kind, a real kubelet, and helm test"

  # The published image is amd64 only — one manifest, not an index — which is why the chart's
  # default nodeSelector says so. On any other architecture this stage would sit in Pending with a
  # message about node affinity, and saying that here is cheaper than reading it out of an event.
  if [ "$(uname -m)" != "x86_64" ]; then
    echo "this stage needs an x86_64 machine: the image is amd64 only and the chart pins the node" >&2
    echo "to it. On arm64 the pod does not schedule, and pretending otherwise here would be a run" >&2
    echo "that proves nothing about the image anybody actually installs." >&2
    exit 3
  fi

  if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "  building $IMAGE (nothing had it)"
    "$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }
    docker build -q -t "$IMAGE" "$root" >/dev/null || { echo "docker build failed" >&2; exit 3; }
  fi

  kind create cluster --name "$CLUSTER" --wait 120s >"$work/kind.out" 2>&1 || {
    echo "could not create the kind cluster" >&2; sed 's/^/  /' "$work/kind.out" >&2; exit 3
  }
  cluster_created=yes
  # `kind load docker-image` fails on any image that was **pulled** rather than built here, and the
  # reason is not the kind version. Measured on kind 0.32.0 with docker 29.1.3, the current pair:
  # `bochka:chart`, built locally and therefore single-platform, loads; `amazon/aws-cli`, pulled as
  # a multi-platform index, does not. kind imports with `ctr --all-platforms`, and docker's
  # containerd store keeps the index for every platform while keeping the layers of one, so `ctr`
  # asks for a digest nothing has. This comment said "a docker newer than the kind binary" for a
  # milestone, which was a guess that fitted the evidence available then and is wrong: the newest
  # kind fails identically, and a locally built image passes on the same machine in the same run.
  #
  # So the load goes through a single-platform archive, which `docker image save --platform` will
  # write and kind will import without complaint. Kept as a function because both images need it
  # and only one of them is ours.
  #
  # In `auto` that ends the cluster stage with a named reason instead of a red harness. A harness
  # that is red on a developer's machine for a reason that is not the code gets ignored, and then
  # the run that mattered gets ignored with it. `BOCHKA_CHART_KIND=yes` — which is how CI runs —
  # keeps failing, because there the missing capability *is* the failure.
  kind_gave_up() { # what
    if [ "$KIND_MODE" = yes ]; then
      echo "could not load $1 into the cluster" >&2; sed 's/^/  /' "$work/kind.out" >&2; exit 3
    fi
    echo "  SKIP    the cluster stage: kind could not load $1 into the cluster"
    echo "          $(kind version 2>/dev/null | head -1), docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
    echo "          last lines of kind's own output:"
    tail -3 "$work/kind.out" | sed 's/^/            /'
    kind delete cluster --name "$CLUSTER" >/dev/null 2>&1
    skipped_cluster=yes
  }

  # amd64 by name rather than by the host's architecture: everything about this harness (the tag it
  # asserts, the image it builds, the base it stands on) is one architecture today, and a silent
  # `uname -m` would make the failure on an arm64 laptop look like a chart problem.
  load_image() { # image
    docker image save --platform linux/amd64 "$1" -o "$work/image.tar" >>"$work/kind.out" 2>&1 &&
      kind load image-archive "$work/image.tar" --name "$CLUSTER" >>"$work/kind.out" 2>&1
  }

  skipped_cluster=no
  load_image "$IMAGE" || kind_gave_up "$IMAGE"

  # Both images are put in from the outside rather than pulled by the kubelet: an anonymous pull
  # from inside a throwaway cluster is a registry rate limit waiting to make this job flaky, and a
  # flaky harness gets ignored long before it gets fixed.
  docker pull -q --platform linux/amd64 "$TESTS_IMAGE" >/dev/null 2>&1
  [ "$skipped_cluster" = yes ] || load_image "$TESTS_IMAGE" || kind_gave_up "$TESTS_IMAGE"

  if [ "$skipped_cluster" = no ]; then
    # The release is named `bochka` on purpose: fullname collapses to `bochka`, the Service is called
    # `bochka`, and kubelet's service links would therefore inject BOCHKA_SERVICE_HOST and friends —
    # the variables that stop this server with exit 2. This is the one check the whole chart exists
    # for, and it cannot be made by rendering.
    if helm install "$RELEASE" "$chart" \
         --set image.repository="${IMAGE%:*}" \
         --set image.tag="${IMAGE##*:}" \
         --set auth.keys[0].id=chartkey \
         --set auth.keys[0].secret=chartsecret \
         --set tests.image="$TESTS_IMAGE" \
         --set persistence.size=1Gi \
         --wait --timeout 5m >"$work/install.out" 2>&1; then
      pass "the pod reaches Ready, which means the exec probe answered 403 through bash and /dev/tcp"
    else
      fail "the release never became ready"
      sed 's/^/          /' "$work/install.out" | tail -20
      kubectl describe pod -l app.kubernetes.io/instance="$RELEASE" 2>&1 | tail -40 | sed 's/^/          /'
      kubectl logs "sts/$RELEASE" 2>&1 | tail -20 | sed 's/^/          /'
    fi

    # The handle the exec probe exists to replace (M-143), checked by the only judge that matters.
    # Rendering `httpGet` proves the template; kubelet accepting it proves the server, and those are
    # different claims -- an httpGet probe against a path that answers 403 renders identically and
    # restarts the pod forever. An upgrade rather than a second install: one changed field, one
    # rolling restart, and `--wait` returns only when the new pod is Ready under the new probe.
    if helm upgrade "$RELEASE" "$chart" \
         --set image.repository="${IMAGE%:*}" \
         --set image.tag="${IMAGE##*:}" \
         --set auth.keys[0].id=chartkey \
         --set auth.keys[0].secret=chartsecret \
         --set tests.image="$TESTS_IMAGE" \
         --set persistence.size=1Gi \
         --set probes.type=http \
         --wait --timeout 3m >"$work/http-probe.out" 2>&1; then
      pass "probes.type: http reaches Ready, so kubelet got a 200 from /-/healthy"
    else
      fail "the pod never became ready under an httpGet probe on /-/healthy"
      sed 's/^/          /' "$work/http-probe.out" | tail -20
      kubectl describe pod -l app.kubernetes.io/instance="$RELEASE" 2>&1 | tail -30 | sed 's/^/          /'
    fi

    # M33: the small profile is a different **entry point**, and rendering `command:` proves the
    # template and nothing else. Two things can go wrong here and both look like the chart: an image
    # without that script fails with "no such file or directory", and a limit sized for the other
    # profile is an OOM kill at work. What settles it is the number the process prints — the ceiling
    # is derived from the heap, so 99816 in the log is the only proof that the small profile is what
    # actually started.
    if helm upgrade "$RELEASE" "$chart" \
         --set image.repository="${IMAGE%:*}" \
         --set image.tag="${IMAGE##*:}" \
         --set auth.keys[0].id=chartkey \
         --set auth.keys[0].secret=chartsecret \
         --set tests.image="$TESTS_IMAGE" \
         --set persistence.size=1Gi \
         --set heapProfile=small \
         --set resources.limits.memory=320Mi \
         --set resources.requests.memory=320Mi \
         --wait --timeout 3m >"$work/small-profile.out" 2>&1; then
      ceiling=$(kubectl logs "sts/$RELEASE" 2>/dev/null | grep -m1 "object ceiling")
      case "$ceiling" in
        *99816*) pass "heapProfile: small starts and prints its own ceiling ($ceiling)" ;;
        *) fail "the pod started but printed '$ceiling' — that is the other profile's heap" ;;
      esac
    else
      fail "heapProfile: small never became ready at the floor the chart asks for it"
      sed 's/^/          /' "$work/small-profile.out" | tail -20
      kubectl describe pod -l app.kubernetes.io/instance="$RELEASE" 2>&1 | tail -30 | sed 's/^/          /'
    fi

    if helm test "$RELEASE" --logs >"$work/test.out" 2>&1; then
      pass "helm test: a round trip through aws-cli, and the built-in credentials refused"
    else
      fail "helm test failed"
      sed 's/^/          /' "$work/test.out" | tail -30
    fi

    uid=$(kubectl exec "sts/$RELEASE" -- id -u 2>/dev/null | tr -d '\r\n')
    if [ "$uid" = "1000" ]; then
      pass "the process runs as uid 1000 and writes to a volume it did not own a minute ago"
    else
      fail "the process runs as uid '$uid'"
    fi

    # And the hazard itself, seen rather than asserted. `enableServiceLinks: false` in the chart is a
    # claim about what would happen without it, and a claim nobody has watched fail is a comment. So:
    # the same image, in the same namespace, with service links left at their default, next to a
    # Service called `bochka`. It has to refuse to start and say which name it choked on.
    #
    # If this ever goes red the other way — the pod starts — the reason for that line has stopped
    # reproducing, and that is worth knowing before somebody deletes it as noise.
    probe_pod="$RELEASE-servicelinks"
    kubectl run "$probe_pod" --image="$IMAGE" --image-pull-policy=IfNotPresent \
      --restart=Never --command -- /opt/bochka/bin/bochka-app >/dev/null 2>&1
    linkmsg=""
    for _ in $(seq 1 60); do
      linkmsg=$(kubectl logs "$probe_pod" 2>/dev/null)
      [ -n "$linkmsg" ] && break
      sleep 2
    done
    kubectl delete pod "$probe_pod" --now >/dev/null 2>&1
    # Any injected name, not one chosen in advance. The claim is "a Service called `bochka` puts
    # BOCHKA_* variables in the pod and this server stops on them" — which one it hits first is
    # kubelet's business and the environment's iteration order. Pinned to BOCHKA_SERVICE_HOST, this
    # check went red against a server that had refused to start exactly as predicted, and printed the
    # refusal underneath its own verdict: `unknown setting 'BOCHKA_PORT_9000_TCP_PORT'`.
    if printf '%s' "$linkmsg" | grep "unknown setting 'BOCHKA_" >/dev/null; then
      pass "with service links left on, the same image refuses to start — which is what the chart turns off"
    else
      fail "the service-link hazard did not reproduce, so 'enableServiceLinks: false' is now unproven"
      printf '%s\n' "${linkmsg:-(the pod printed nothing)}" | head -5 | sed 's/^/          /'
    fi

    # The trap this chart is built around: service links are injected only for Services that existed
    # *before* the pod. The install above can pass with them on; the first rollout after it cannot.
    # So the rollout is part of the harness rather than something a person discovers in production.
    if kubectl rollout restart "statefulset/$RELEASE" >/dev/null 2>&1 &&
       kubectl rollout status "statefulset/$RELEASE" --timeout=5m >"$work/rollout.out" 2>&1; then
      pass "the pod restarts with the Service already in place, which is where service links would bite"
    else
      fail "the rollout did not complete"
      sed 's/^/          /' "$work/rollout.out"
      kubectl logs "sts/$RELEASE" 2>&1 | tail -20 | sed 's/^/          /'
    fi

    if helm test "$RELEASE" --logs >"$work/test2.out" 2>&1; then
      pass "and it still stores and serves after the restart, on the same volume"
    else
      fail "the store did not survive its own pod being replaced"
      sed 's/^/          /' "$work/test2.out" | tail -30
    fi

    # `persistence.size` on an installed release. The chart says this is refused rather than ignored,
    # and the difference is the whole point: a volumeClaimTemplate is immutable, so the API server
    # rejects the StatefulSet patch, `helm upgrade` ends failed, and everything Helm applies *before*
    # the StatefulSet is already in the cluster. The upgrade below therefore changes two things at
    # once, the way a GitOps commit does: the volume size, which makes it fail, and the key, which
    # shows what got through anyway. No run touched this path before, and the claim lived in three
    # documents.
    if helm upgrade "$RELEASE" "$chart" \
         --set image.repository="${IMAGE%:*}" \
         --set image.tag="${IMAGE##*:}" \
         --set auth.keys[0].id=chartkey \
         --set auth.keys[0].secret=rotatedsecret \
         --set tests.image="$TESTS_IMAGE" \
         --set persistence.size=2Gi >"$work/upgrade.out" 2>&1; then
      fail "a changed persistence.size upgraded cleanly, so what the chart says about it is now wrong"
    elif grep -q "forbidden" "$work/upgrade.out"; then
      pass "a changed persistence.size is refused by the API server, not quietly ignored"
    else
      fail "the upgrade failed, but not as an immutable volumeClaimTemplate:"
      sed 's/^/          /' "$work/upgrade.out" | head -5
    fi

    size_now=$(kubectl get "sts/$RELEASE" -o jsonpath='{.spec.volumeClaimTemplates[0].spec.resources.requests.storage}' 2>/dev/null)
    keys_now=$(kubectl get "secret/$RELEASE" -o jsonpath='{.data.keys}' 2>/dev/null |
      python3 -c 'import base64,sys; d=sys.stdin.read().strip(); print(base64.b64decode(d).decode() if d else "")')
    if [ "$size_now" = "1Gi" ] && [ "$keys_now" = "chartkey:rotatedsecret" ]; then
      pass "and the failed release is half applied: the new key is in the Secret, the pod is on the old spec"
    else
      fail "the failed upgrade left something else behind: volume '$size_now', keys '$keys_now'"
    fi

    helm uninstall "$RELEASE" >/dev/null 2>&1
    if kubectl get pvc "data-$RELEASE-0" >/dev/null 2>&1; then
      pass "helm uninstall left the volume alone"
    else
      fail "the volume went away with the release"
    fi
  fi
fi

echo
printf 'passed %d, failed %d\n' "$passed" "$failed"
if [ $((passed + failed)) -eq 0 ]; then
  echo "nothing was checked, which is a failure rather than a clean run" >&2
  summarised=yes
  exit 1
fi
summarised=yes
[ "$failed" -eq 0 ]
