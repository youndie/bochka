#!/usr/bin/env bash
# The two parsers a request passes on its way in, asked to disagree (M-281).
#
# Request smuggling is never one parser's bug: it is two of them reading one byte stream
# differently. This repository already refuses the classic framings in `HttpRequestParser` and has
# unit tests for them — what those tests cannot say is what happens when nginx is in front, because
# they never see nginx. And in front is where nginx is: `deploy/nginx/nginx.conf` is the deployment
# this project documents, so that is the configuration used here rather than one written for the
# test.
#
#   ./ci/smuggling.sh
#
# The oracle is not the status code. It is **how many responses come back for one request**: a
# second response means somebody read a second request, which is the mechanism itself. Zero is
# allowed — hanging up on a framing you refuse to touch is a legitimate answer.
set -uo pipefail

readonly PORT=${BOCHKA_PORT:-9000}
readonly TLS_PORT=${BOCHKA_TLS_PORT:-443}

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
log="$work/bochka.log"
ran=no

cleanup() {
  local status=$?
  [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
  docker rm -f bochka-smuggling >/dev/null 2>&1
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null
  # In the trap, where an edit above cannot step over it: a run that reached no case at all exits
  # zero on its own and reads exactly like a pass.
  if [ "$ran" = no ]; then
    echo "no framing was ever sent: that is a failure, not an empty result" >&2
    [ "$status" -eq 0 ] && status=1
  fi
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; nginx cannot be put in front" >&2; exit 3; }
command -v python3 >/dev/null || { echo "python3 sends the bytes" >&2; exit 3; }

echo "building the distribution"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=127.0.0.1 BOCHKA_LOG=1 BOCHKA_DATA_DIR="$work/data" \
  "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!

for _ in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.2
done
if ! (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then
  echo "bochka did not come up; log follows" >&2; cat "$log" >&2; exit 3
fi

mkdir -p "$work/tls"
# nginx:alpine carries no openssl binary, so the certificate comes from an image that does.
docker run --rm -v "$work/tls:/tls" alpine/openssl:latest \
  req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj "/CN=127.0.0.1" -addext "subjectAltName=IP:127.0.0.1" \
  -keyout /tls/key.pem -out /tls/cert.pem >/dev/null 2>&1 ||
  { echo "the certificate could not be generated" >&2; exit 3; }

docker rm -f bochka-smuggling >/dev/null 2>&1
# The deployment's own configuration, mounted rather than rewritten. A config written for this test
# would test a pair of parsers nobody runs, which is the failure this harness exists to avoid.
docker run -d --name bochka-smuggling --network host \
  -v "$root/deploy/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v "$work/tls:/etc/nginx/tls:ro" nginx:alpine >/dev/null 2>&1 ||
  { echo "nginx would not start" >&2; exit 3; }

for _ in $(seq 1 25); do
  (exec 3<>/dev/tcp/127.0.0.1/$TLS_PORT) 2>/dev/null && break
  sleep 0.2
done
if ! (exec 3<>/dev/tcp/127.0.0.1/$TLS_PORT) 2>/dev/null; then
  echo "nginx did not come up; its log follows" >&2
  docker logs bochka-smuggling 2>&1 | tail -20 >&2
  exit 3
fi

echo "bochka on $PORT, nginx from deploy/ on $TLS_PORT"
echo
ran=yes
python3 "$root/ci/smuggling.py" "$PORT" "$TLS_PORT"
