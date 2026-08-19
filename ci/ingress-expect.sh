#!/usr/bin/env bash
# M-144: what happens to `Expect: 100-continue` when ingress-nginx is in front.
#
# bochka refuses a bad signature from the head alone, before a byte of body is read (research
# §1.2.2) -- that is what makes a 403 cost nothing instead of costing five gigabytes. Whether that
# property survives a proxy is a question about the proxy, and reading its documentation is not an
# answer: this repository has already paid for the difference between "our response is correct" and
# "what the client received", when nginx replaced the ETag on five green tests.
#
# So both paths, same request, same body, and the number that decides is how many bytes the client
# managed to upload before it was refused.
#
#   ./ci/ingress-expect.sh          # builds the image if nothing has it, makes a cluster, tears it down
#
# Not part of check.yml and not part of ci/helm-chart.sh: it installs an ingress controller and
# takes minutes. It is a measurement, and what it measured is written down in docs/measurements.md
# with the versions it was measured on.
set -uo pipefail

readonly IMAGE=${BOCHKA_IMAGE:-bochka:chart}
readonly CLUSTER=${BOCHKA_INGRESS_CLUSTER:-bochka-ingress}
readonly HOST=${BOCHKA_INGRESS_HOST:-s3.example.com}
readonly RELEASE=bochka
readonly MIB=${BOCHKA_INGRESS_MIB:-64}

root=$(cd "$(dirname "$0")/.." && pwd)
chart="$root/deploy/helm/bochka"
work=$(mktemp -d)
passed=0; failed=0
pass() { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail() { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }
note() { printf '  ----    %s\n' "$1"; }

cleanup() {
  local status=$?
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1
  rm -rf "$work"
  # A summary that is not printed is a run that proves nothing, and a script that stops early looks
  # exactly like a script that found nothing wrong. Same guard as ci/s3-tests.sh, same reason.
  [ "$status" = 0 ] || echo "this run ended without a summary, which is a failure rather than a pass"
  exit "$status"
}
trap cleanup EXIT

for tool in kind kubectl helm docker openssl curl; do
  command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 3; }
done

docker image inspect "$IMAGE" >/dev/null 2>&1 || {
  "$root/gradlew" -p "$root" -q :bochka-app:installDist || exit 3
  docker build -q -t "$IMAGE" "$root" >/dev/null || exit 3
}

cat >"$work/kind.yaml" <<YAML
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 443
        hostPort: 8443
        protocol: TCP
YAML

echo "bringing up a cluster with ingress-nginx"
kind create cluster --name "$CLUSTER" --config "$work/kind.yaml" --wait 120s >"$work/kind.out" 2>&1 || {
  echo "could not create the cluster" >&2; tail -5 "$work/kind.out" >&2; exit 3
}
# A single-platform archive, for the same reason ci/helm-chart.sh uses one: kind imports with
# `ctr --all-platforms` and docker's containerd store holds an index whose other platforms it never
# fetched.
docker image save --platform linux/amd64 "$IMAGE" -o "$work/image.tar" >>"$work/kind.out" 2>&1 &&
  kind load image-archive "$work/image.tar" --name "$CLUSTER" >>"$work/kind.out" 2>&1 || {
  echo "could not load $IMAGE into the cluster" >&2; tail -5 "$work/kind.out" >&2; exit 3
}

kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml \
  >"$work/ingress.out" 2>&1 || { echo "ingress-nginx did not apply" >&2; tail -5 "$work/ingress.out" >&2; exit 3; }
kubectl wait --namespace ingress-nginx --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller --timeout=180s >>"$work/ingress.out" 2>&1 || {
  echo "the ingress controller never became ready" >&2; tail -10 "$work/ingress.out" >&2; exit 3
}
controller=$(kubectl -n ingress-nginx get deploy ingress-nginx-controller -o jsonpath='{.spec.template.spec.containers[0].image}')
note "controller $controller"

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "$work/tls.key" -out "$work/tls.crt" -subj "/CN=$HOST" \
  -addext "subjectAltName=DNS:$HOST" >/dev/null 2>&1
kubectl create secret tls "$HOST-tls" --cert="$work/tls.crt" --key="$work/tls.key" >/dev/null

helm install "$RELEASE" "$chart" \
  --set image.repository="${IMAGE%:*}" --set image.tag="${IMAGE##*:}" \
  --set auth.keys[0].id=ingresskey --set auth.keys[0].secret=ingresssecret \
  --set persistence.size=1Gi \
  --set ingress.enabled=true --set ingress.className=nginx \
  --set "ingress.hosts[0].host=$HOST" \
  --set "ingress.hosts[0].paths[0].path=/" --set "ingress.hosts[0].paths[0].pathType=Prefix" \
  --set "ingress.tls[0].secretName=$HOST-tls" --set "ingress.tls[0].hosts[0]=$HOST" \
  --wait --timeout 5m >"$work/install.out" 2>&1 || {
  echo "the release never became ready" >&2; tail -20 "$work/install.out" >&2; exit 3
}
pass "the release is up with an Ingress for $HOST"

head -c $((MIB * 1024 * 1024)) /dev/zero >"$work/body.bin"

# The control, and it is the point of having one: the same refusal straight at the Service says what
# this server does on its own, so anything different through the Ingress belongs to the Ingress.
kubectl port-forward "svc/$RELEASE" 19100:9000 >"$work/pf.out" 2>&1 &
pf=$!
for _ in $(seq 1 40); do curl -sf -o /dev/null "http://127.0.0.1:19100/-/healthy" && break; sleep 0.5; done

measure() { # url, extra curl args...
  local url=$1; shift
  curl -s -o /dev/null -k \
    -X PUT --data-binary "@$work/body.bin" \
    -H 'Expect: 100-continue' \
    -H 'Authorization: AWS4-HMAC-SHA256 Credential=ingresskey/20260819/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=deadbeef' \
    -H 'x-amz-content-sha256: UNSIGNED-PAYLOAD' \
    -H 'x-amz-date: 20260819T000000Z' \
    -w '%{http_code} %{size_upload} %{time_total}' \
    "$@" "$url" 2>/dev/null
}

direct=$(measure "http://127.0.0.1:19100/photos/big.bin")
kill "$pf" 2>/dev/null; wait "$pf" 2>/dev/null
through=$(measure "https://$HOST:8443/photos/big.bin" --resolve "$HOST:8443:127.0.0.1")

note "straight at the Service: status/uploaded-bytes/seconds = $direct"
note "through ingress-nginx:   status/uploaded-bytes/seconds = $through"

read -r direct_code direct_bytes direct_time <<<"$direct"
read -r through_code through_bytes through_time <<<"$through"
body_bytes=$((MIB * 1024 * 1024))

[ "$direct_code" = 403 ] &&
  pass "straight at the Service the refusal is a 403" ||
  fail "straight at the Service the answer was $direct_code, not 403"

# The claim under test. Anything materially short of the whole body means the refusal reached the
# client before the upload finished; the whole body means the proxy took it all first.
if [ "$direct_bytes" -lt $((body_bytes / 2)) ]; then
  pass "and it costs $direct_bytes bytes of the $body_bytes-byte body, not all of them"
else
  fail "and yet the whole body went up ($direct_bytes bytes): the head-only refusal is not working"
fi

[ "$through_code" = 403 ] &&
  pass "through ingress-nginx the refusal is still a 403" ||
  fail "through ingress-nginx the answer was $through_code, not 403"

if [ "$through_bytes" -lt $((body_bytes / 2)) ]; then
  pass "and the client still stops early ($through_bytes bytes of $body_bytes) — the property survives the proxy"
else
  note "the client uploaded $through_bytes of $body_bytes bytes through the proxy, against $direct_bytes direct"
  note "so the refusal does not reach the client early through this controller, and the chart must not promise it does"
fi

echo
printf 'passed %d, failed %d\n' "$passed" "$failed"
[ "$failed" = 0 ]
