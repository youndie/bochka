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
for set in minimal full emptydir existing-claim existing-secret; do
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
for set in minimal full emptydir existing-claim existing-secret; do
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
expect_in "$work/minimal.yaml" 'dev/tcp' "the probe is a request, not an open socket"
expect_not_in "$work/minimal.yaml" 'httpGet' "the default probe is still exec: appVersion has no health handle"
expect_not_in "$work/minimal.yaml" 'JAVA_OPTS' "the runtime profile is never touched from the chart"
expect_not_in "$work/minimal.yaml" 'terminationGracePeriodSeconds' "no invented grace period"
expect_not_in "$work/minimal.yaml" 'preStop' "no preStop hook pretending to drain connections"

# BOCHKA_LOG is compared to the literal "1", so the boolean has to be converted rather than printed.
if grep -A1 'name: BOCHKA_LOG' "$work/full.yaml" | grep -q 'value: "1"'; then
  pass 'log: true renders as "1" and not as "true"'
else
  fail 'log: true did not render as "1"'
fi
if grep -A1 'name: BOCHKA_LOG' "$work/minimal.yaml" | grep -q 'value: "0"'; then
  pass 'log: false renders as "0"'
else
  fail 'log: false did not render as "0"'
fi

# housekeepingMinutes: 0 is "disabled", and `default` in a template would have turned it back to 60.
if grep -A1 'name: BOCHKA_HOUSEKEEPING_MINUTES' "$work/full.yaml" | grep -q 'value: "0"'; then
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

expect_in "$work/minimal.yaml" 'kubernetes.io/arch: amd64' "the arch pin is on by default, because the image has one architecture"
# Single quotes, and it is not style: in double quotes the backticks below were command
# substitution, so this line ran `nodeSelector: null` in the shell and printed its description with
# the halves missing.
expect_not_in "$work/full.yaml" 'kubernetes.io/arch' 'and "nodeSelector: null" actually removes it — "{}" would merge and quietly not'
expect_in "$work/full.yaml" 'BOCHKA_VIRTUAL_HOST_SUFFIXES' "virtual-host suffixes reach the process"
expect_in "$work/full.yaml" 'proxy-request-buffering' "the ingress carries the annotations nginx.conf calls non-optional"
expect_in "$work/full.yaml" 'tcpSocket' "probes.type: tcp renders the socket probe"
expect_in "$work/emptydir.yaml" 'emptyDir' "persistence.enabled: false is allowed"
expect_not_in "$work/emptydir.yaml" 'volumeClaimTemplates' "and claims nothing while it is off"
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
refuses bad-ingress-no-tls     "without ingress.tls"   "an Ingress with no certificate"
refuses bad-ingress-wildcard   "virtualHostSuffixes"   "a wildcard host the router was never told about"
refuses bad-ingress-suffix     "no rule for"           "a virtual-host suffix with no wildcard rule, which the controller answers itself"
refuses bad-access-mode        "accessModes"           "ReadWriteMany, which promises a second writer"
refuses bad-small-memory       "runtime profile needs" "a memory limit under the profile, where the OOM kill beats the object ceiling"
refuses bad-probe-timing       "initialDelaySeconds"   "a probe field the template renders nowhere"
refuses bad-unknown-value      "replicaCount"          "a values key nobody reads"
# `boolean` alone, because the rest of that sentence belongs to helm rather than to us: it says
# "Expected: boolean, given: string" up to 3.17 and "got string, want boolean" after, and this
# check went red on a helm upgrade while the chart refused the value exactly as it should. What is
# being asserted is that the refusal is about the type, and that word is the whole of it.
refuses bad-log-type           "boolean"                "a non-boolean log, which would be logging quietly off"

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
  # `kind load` is where a docker that is newer than the kind binary shows up, and it shows up as
  # a missing blob rather than as a version complaint: kind imports with `ctr --all-platforms`, and
  # an image pulled into docker's containerd store holds the index for every platform while holding
  # the layers of one, so `ctr` asks for a digest nothing has. Measured here on docker 29.1.3 with
  # kind 0.27.0; the same script and the same tree pass on CI, where kind is newer.
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

  skipped_cluster=no
  kind load docker-image "$IMAGE" --name "$CLUSTER" >>"$work/kind.out" 2>&1 || kind_gave_up "$IMAGE"

  # Both images are put in from the outside rather than pulled by the kubelet: an anonymous pull
  # from inside a throwaway cluster is a registry rate limit waiting to make this job flaky, and a
  # flaky harness gets ignored long before it gets fixed.
  docker pull -q "$TESTS_IMAGE" >/dev/null 2>&1
  [ "$skipped_cluster" = yes ] ||
    kind load docker-image "$TESTS_IMAGE" --name "$CLUSTER" >>"$work/kind.out" 2>&1 ||
    kind_gave_up "$TESTS_IMAGE"

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
    if printf '%s' "$linkmsg" | grep -q "unknown setting 'BOCHKA_"; then
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
