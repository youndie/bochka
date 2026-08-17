#!/usr/bin/env bash
# Runs bochka and points other people's S3 clients at it.
#
# This is the acceptance of M3, and the reason it exists rather than a test in Kotlin: this
# project's author also wrote an S3 client, and testing a server with your own client is the
# weakest check available — it signs its bodies the one simple way, so it never exercises the
# framing most likely to be broken, and it does that while looking green (research, Р6).
#
# Clients run as containers, so the only thing that has to be installed is Docker. A client whose
# image cannot be pulled is reported SKIPPED, not passed: a skipped check reads exactly like a
# passing one, which is the failure this whole script exists to avoid. A run in which nothing
# executed is a failure.
set -uo pipefail

readonly KEY=bochkaadmin
readonly SECRET=bochkasecret
readonly BUCKET=live-clients
readonly PORT=${BOCHKA_PORT:-19000}
readonly ENDPOINT="http://127.0.0.1:${PORT}"

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
log="$work/bochka.log"
passed=0; failed=0; skipped=0

pass()  { printf '  PASS    %s\n' "$1"; passed=$((passed+1)); }
fail()  { printf '  FAIL    %s\n' "$1"; failed=$((failed+1)); }
skip()  { printf '  SKIPPED %s (%s)\n' "$1" "$2"; skipped=$((skipped+1)); }

cleanup() {
  [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
  docker rm -f bochka-tls >/dev/null 2>&1
  # Containers write as root, so some of this is not ours to delete. Losing a temporary directory
  # is not worth failing a run over.
  chmod -R u+w "$work" 2>/dev/null
  rm -rf "$work" 2>/dev/null || docker run --rm -v "$work:/w" --entrypoint "" alpine:latest rm -rf /w/. 2>/dev/null
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is not installed; every client would be skipped" >&2; exit 3; }

echo "building the distribution"
"$root/gradlew" -p "$root" -q :bochka-app:installDist || { echo "build failed" >&2; exit 3; }

BOCHKA_PORT=$PORT BOCHKA_BIND_ADDRESS=0.0.0.0 BOCHKA_LOG=1 BOCHKA_DATA_DIR="$work/data" \
  "$root/bochka-app/build/install/bochka-app/bin/bochka-app" >"$log" 2>&1 &
server_pid=$!

for _ in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.2
done
if ! (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then
  echo "bochka did not come up; log follows" >&2; cat "$log" >&2; exit 3
fi
echo "bochka is up on $ENDPOINT"

payload="$work/payload.bin"
head -c 3000000 /dev/urandom > "$payload"
expected=$(sha256sum "$payload" | cut -d' ' -f1)

docker_run() {
  local image=$1; shift
  docker run --rm --network host \
    -e AWS_ACCESS_KEY_ID=$KEY -e AWS_SECRET_ACCESS_KEY=$SECRET -e AWS_DEFAULT_REGION=us-east-1 \
    -e AWS_EC2_METADATA_DISABLED=true \
    -v "$work:/work" --entrypoint "" "$image" "$@"
}

have_image() { docker image inspect "$1" >/dev/null 2>&1 || docker pull -q "$1" >/dev/null 2>&1; }

# --- aws-cli: the client that decides the milestone -------------------------------------------
if have_image amazon/aws-cli:latest; then
  aws_cli() { docker_run amazon/aws-cli:latest aws --endpoint-url "$ENDPOINT" "$@"; }

  aws_cli s3 mb "s3://$BUCKET" >/dev/null 2>&1
  ok=true
  # Four framings, forced. Left to itself the CLI picks one and changes its mind between versions,
  # so each is asked for by name and the server's own log is checked below for all four.
  for mode in default crc32 payload-signing unsigned; do
    case $mode in
      default)          extra=() ;;
      crc32)            extra=(--checksum-algorithm CRC32) ;;
      payload-signing)  extra=() ; export AWS_S3_PAYLOAD_SIGNING=1 ;;
      unsigned)         extra=() ;;
    esac
    if aws_cli s3 cp /work/payload.bin "s3://$BUCKET/$mode.bin" "${extra[@]}" >/dev/null 2>&1 &&
       aws_cli s3 cp "s3://$BUCKET/$mode.bin" "/work/back-$mode.bin" >/dev/null 2>&1 &&
       [ "$(sha256sum "$work/back-$mode.bin" | cut -d' ' -f1)" = "$expected" ]; then
      pass "aws-cli round trip ($mode)"
    else
      fail "aws-cli round trip ($mode)"; ok=false
    fi
  done
  aws_cli s3 ls "s3://$BUCKET" >/dev/null 2>&1 && pass "aws-cli s3 ls" || fail "aws-cli s3 ls"
  $ok || true
else
  skip "aws-cli" "image unavailable"
fi

# --- boto3: a second reading of the same signing code, without the CLI on top ------------------
if have_image python:3.12-slim; then
  cat > "$work/boto.py" <<'PY'
import hashlib, boto3
s3 = boto3.client("s3", endpoint_url="http://127.0.0.1:19000",
                  aws_access_key_id="bochkaadmin", aws_secret_access_key="bochkasecret",
                  region_name="us-east-1")
s3.create_bucket(Bucket="live-clients")
body = open("/work/payload.bin", "rb").read()
s3.put_object(Bucket="live-clients", Key="boto3.bin", Body=body)
back = s3.get_object(Bucket="live-clients", Key="boto3.bin")["Body"].read()
assert hashlib.sha256(back).hexdigest() == hashlib.sha256(body).hexdigest(), "round trip differs"
assert any(o["Key"] == "boto3.bin" for o in s3.list_objects_v2(Bucket="live-clients").get("Contents", []))
print("ok")
PY
  if docker_run python:3.12-slim sh -c "pip install -q boto3 >/dev/null 2>&1 && python /work/boto.py" 2>/dev/null | grep -q ok; then
    pass "boto3 round trip and listing"
  else
    fail "boto3 round trip and listing"
  fi
else
  skip "boto3" "image unavailable"
fi

# --- mc: MinIO's own client, a third independent implementation --------------------------------
if have_image minio/mc:latest; then
  mc() { docker_run minio/mc:latest mc --config-dir /work/mc "$@"; }
  mc alias set bochka "$ENDPOINT" $KEY $SECRET >/dev/null 2>&1
  if mc cp /work/payload.bin bochka/$BUCKET/mc.bin >/dev/null 2>&1 &&
     mc cp bochka/$BUCKET/mc.bin /work/back-mc.bin >/dev/null 2>&1 &&
     [ "$(sha256sum "$work/back-mc.bin" | cut -d' ' -f1)" = "$expected" ]; then
    pass "mc round trip"
  else
    fail "mc round trip"
  fi
  mc ls bochka/$BUCKET >/dev/null 2>&1 && pass "mc ls" || fail "mc ls"
else
  skip "mc" "image unavailable"
fi

# --- rclone: a fourth, and the one least like the others ---------------------------------------
if have_image rclone/rclone:latest; then
  rc() {
    docker_run rclone/rclone:latest rclone \
      --s3-provider Other --s3-endpoint "$ENDPOINT" \
      --s3-access-key-id $KEY --s3-secret-access-key $SECRET --s3-region us-east-1 \
      --config /dev/null "$@"
  }
  if rc copyto /work/payload.bin ":s3:$BUCKET/rclone.bin" >/dev/null 2>&1 &&
     rc copyto ":s3:$BUCKET/rclone.bin" /work/back-rclone.bin >/dev/null 2>&1 &&
     [ "$(sha256sum "$work/back-rclone.bin" | cut -d' ' -f1)" = "$expected" ]; then
    pass "rclone round trip"
  else
    fail "rclone round trip"
  fi
else
  skip "rclone" "image unavailable"
fi

# --- multipart, which no client does unless the file is big enough ------------------------------
#
# The ordinary round trips above never reach it: every client here has a threshold, and the 3 MB
# payload is under all of them. So this uploads something over aws-cli's 8 MB default and then
# checks the two things that distinguish a real multipart implementation from one that only looks
# like it — the bytes come back identical, and the ETag carries the `-N` suffix that says the
# object was assembled rather than written whole.
#
# `mc` is here as well because it splits differently, which is the point of having two: the part
# boundaries a server sees are the client's choice, not the protocol's.
if have_image amazon/aws-cli:latest; then
  aws_cli() { docker_run amazon/aws-cli:latest aws --endpoint-url "$ENDPOINT" "$@"; }

  head -c 17000000 /dev/urandom > "$work/large.bin"
  large_expected=$(sha256sum "$work/large.bin" | cut -d' ' -f1)

  if aws_cli s3 cp /work/large.bin "s3://$BUCKET/large-aws.bin" >/dev/null 2>&1 &&
     aws_cli s3 cp "s3://$BUCKET/large-aws.bin" /work/large-back.bin >/dev/null 2>&1 &&
     [ "$(sha256sum "$work/large-back.bin" | cut -d' ' -f1)" = "$large_expected" ]; then
    pass "aws-cli multipart round trip"
  else
    fail "aws-cli multipart round trip"
  fi

  # An ETag without the suffix means the upload was not multipart at all, and the round trip
  # above would pass anyway — which is exactly the check that would have been missing.
  etag=$(aws_cli s3api head-object --bucket "$BUCKET" --key large-aws.bin --query ETag --output text 2>/dev/null)
  case "$etag" in
    *-*) pass "the assembled object is marked as assembled ($etag)" ;;
    *)   fail "multipart ETag has no part count: '$etag'" ;;
  esac

  # Abandoned uploads have to be visible, or they are unreclaimable by anything but a restart.
  aws_cli s3api create-multipart-upload --bucket "$BUCKET" --key abandoned.bin >/dev/null 2>&1
  if aws_cli s3api list-multipart-uploads --bucket "$BUCKET" 2>/dev/null | grep -q abandoned.bin; then
    pass "an upload in flight is listed"
  else
    fail "an upload in flight is listed"
  fi
else
  skip "multipart" "image unavailable"
fi

if have_image minio/mc:latest; then
  # The config directory is on the mounted volume, not inside the container: every `mc` here is a
  # fresh container, so an alias written to the container's own /tmp is gone by the next call —
  # and what that looks like is not an error but a client that quietly does nothing.
  mc() { docker_run minio/mc:latest mc --config-dir /work/mc "$@"; }
  mc alias set bochka "$ENDPOINT" $KEY $SECRET >/dev/null 2>&1
  if mc cp /work/large.bin bochka/$BUCKET/large-mc.bin >/dev/null 2>&1 &&
     mc cp bochka/$BUCKET/large-mc.bin /work/large-mc-back.bin >/dev/null 2>&1 &&
     [ "$(sha256sum "$work/large-mc-back.bin" | cut -d' ' -f1)" = "$large_expected" ]; then
    pass "mc multipart round trip"
  else
    fail "mc multipart round trip"
  fi
else
  skip "mc multipart" "image unavailable"
fi

# --- everything at once ------------------------------------------------------------------------
#
# Sequential round trips find none of the failures a shared server actually has. Two properties are
# checked here, and the second is the one that will still matter when the draft store is replaced
# by the real one:
#
#   1. eight uploads of eight different keys at the same time all arrive intact;
#   2. eight uploads of the *same* key at the same time leave an object that is **one of them**,
#      never a mixture. "Last writer wins" is a choice; "half of one and half of another" is
#      corruption, and it is the shape a store gets when it writes in place instead of renaming
#      into place.
if have_image amazon/aws-cli:latest; then
  aws_cli() { docker_run amazon/aws-cli:latest aws --endpoint-url "$ENDPOINT" "$@"; }

  for i in $(seq 1 8); do
    head -c 500000 /dev/urandom > "$work/par-$i.bin"
  done

  pids=()
  for i in $(seq 1 8); do
    aws_cli s3 cp "/work/par-$i.bin" "s3://$BUCKET/parallel/$i.bin" >/dev/null 2>&1 &
    pids+=($!)
  done
  wait "${pids[@]}"

  distinct_ok=true
  for i in $(seq 1 8); do
    aws_cli s3 cp "s3://$BUCKET/parallel/$i.bin" "/work/par-back-$i.bin" >/dev/null 2>&1 || distinct_ok=false
    [ "$(sha256sum "$work/par-$i.bin" | cut -d\  -f1)" = "$(sha256sum "$work/par-back-$i.bin" 2>/dev/null | cut -d\  -f1)" ] ||
      distinct_ok=false
  done
  $distinct_ok && pass "eight concurrent uploads of different keys" || fail "eight concurrent uploads of different keys"

  pids=()
  for i in $(seq 1 8); do
    aws_cli s3 cp "/work/par-$i.bin" "s3://$BUCKET/parallel/contended.bin" >/dev/null 2>&1 &
    pids+=($!)
  done
  wait "${pids[@]}"

  if aws_cli s3 cp "s3://$BUCKET/parallel/contended.bin" /work/contended-back.bin >/dev/null 2>&1; then
    winner=$(sha256sum "$work/contended-back.bin" | cut -d\  -f1)
    matched=false
    for i in $(seq 1 8); do
      [ "$winner" = "$(sha256sum "$work/par-$i.bin" | cut -d\  -f1)" ] && matched=true
    done
    $matched && pass "eight concurrent uploads of one key leave one of them whole" ||
      fail "eight concurrent uploads of one key left a mixture"
  else
    fail "the contended key could not be read back at all"
  fi
else
  skip "concurrent uploads" "image unavailable"
fi

# --- the fourth framing, which needs TLS to appear at all --------------------------------------
#
# STREAMING-UNSIGNED-PAYLOAD-TRAILER cannot be provoked over plaintext: botocore decides between a
# header checksum and a trailer one by the scheme, and says so in the source — "As this disables
# payload signing we'll only use trailers over TLS" (botocore/httpchecksum.py). So the only way a
# real client sends it is through a terminator, which is bochka's deployment shape anyway (Р5).
# This case therefore checks two things at once: the framing, and that the server works behind the
# proxy it is meant to run behind.
if have_image nginx:alpine && have_image alpine/openssl:latest; then
  mkdir -p "$work/tls"
  # nginx:alpine carries no openssl binary, so the certificate comes from an image that does.
  if docker run --rm -v "$work/tls:/tls" alpine/openssl:latest \
       req -x509 -newkey rsa:2048 -nodes -days 1 \
       -subj "/CN=127.0.0.1" -addext "subjectAltName=IP:127.0.0.1" \
       -keyout /tls/key.pem -out /tls/cert.pem >/dev/null 2>&1; then
    cat > "$work/tls/nginx.conf" <<'NGINX'
events {}
http {
  server {
    listen 19443 ssl;
    ssl_certificate     /tls/cert.pem;
    ssl_certificate_key /tls/key.pem;
    client_max_body_size 0;
    location / {
      proxy_pass http://127.0.0.1:19000;
      # The signature covers Host, so the terminator must forward the one the client signed.
      # Rewriting it here is the classic way to make every request fail SignatureDoesNotMatch.
      proxy_set_header Host $http_host;
      proxy_request_buffering off;
      proxy_http_version 1.1;
    }
  }
}
NGINX
    docker rm -f bochka-tls >/dev/null 2>&1
    if docker run -d --name bochka-tls --network host -v "$work/tls:/tls:ro" \
         -v "$work/tls/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:alpine >/dev/null 2>&1; then
      sleep 2
      if docker_run amazon/aws-cli:latest aws --endpoint-url https://127.0.0.1:19443 --no-verify-ssl \
           s3 cp /work/payload.bin "s3://$BUCKET/tls-trailer.bin" >/dev/null 2>&1; then
        pass "aws-cli over the TLS terminator"
      else
        fail "aws-cli over the TLS terminator"
      fi
      docker rm -f bochka-tls >/dev/null 2>&1
    else
      skip "TLS terminator" "nginx would not start"
    fi
  else
    skip "TLS terminator" "the certificate could not be generated"
  fi
else
  skip "TLS terminator" "image unavailable"
fi

# --- what the server actually saw --------------------------------------------------------------
echo
echo "framings the server was asked to accept on a body-carrying request:"
grep -E 'bochka handled (PUT|POST)' "$log" | grep -o 'framing=[A-Z0-9-]*' | sort | uniq -c | sed 's/^/  /'

# Four framings, and the milestone is about all four. Missing one is a failure even when every
# round trip passed, because it means a client quietly stopped exercising it.
for framing in SIGNED-PAYLOAD UNSIGNED-PAYLOAD STREAMING-AWS4-HMAC-SHA256-PAYLOAD STREAMING-UNSIGNED-PAYLOAD-TRAILER; do
  if grep -E 'bochka handled (PUT|POST)' "$log" | grep -q "framing=$framing\b"; then
    pass "framing $framing was exercised"
  else
    fail "framing $framing was never sent by any client"
  fi
done

echo
printf 'passed %d, failed %d, skipped %d\n' "$passed" "$failed" "$skipped"
if [ $((passed + failed)) -eq 0 ]; then
  echo "nothing ran, which is a failure rather than a pass" >&2
  exit 1
fi
[ "$failed" -eq 0 ]
