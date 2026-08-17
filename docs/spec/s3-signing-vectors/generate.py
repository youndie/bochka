#!/usr/bin/env python3
"""Regenerate the S3 signing vectors from botocore.

AWS publishes test vectors for the generic SigV4 (../aws-sig-v4-test-suite) but none for the two
places S3 departs from it: the path is signed verbatim, and presigned URLs put the signature in
the query string. These vectors fill that gap by asking the reference implementation — the code
that signs for `aws-cli` — what the answer is.

Run it in a virtualenv, from this directory:

    python3 -m venv .venv && .venv/bin/pip install botocore
    .venv/bin/python generate.py

It rewrites the case directories in place. The botocore version used is written into
`generated-with.txt`, because a different version could legitimately produce different output and
that would otherwise look like a regression in the Kotlin code.
"""

import datetime
import os
import shutil
from urllib.parse import quote

import botocore
import botocore.auth
from botocore.auth import S3SigV4Auth, S3SigV4QueryAuth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials

# The same fixed inputs the official suite uses, so the two sets of vectors read alike.
ACCESS_KEY = "AKIDEXAMPLE"
SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
# A realistic token: it contains `/`, `+` and `=`, which is what makes it worth signing.
SESSION_TOKEN = "AQoDYXdzEPT//////////wEXAMPLEtc764bNrC9SAPBSM22wDOk4x4H+IZ8j4FZTwdQW=="
REGION = "us-east-1"
SERVICE = "s3"
DATE = datetime.datetime(2015, 8, 30, 12, 36, 0, tzinfo=datetime.timezone.utc)

HERE = os.path.dirname(os.path.abspath(__file__))

# Keys chosen for the characters that break a naive encoder: a space, a plus, a tilde, non-ASCII,
# a surrogate pair, repeated slashes and dot segments S3 must not normalise away.
KEYS = [
    "hello.txt",
    "my dir/file.txt",
    "a+b",
    "a~b",
    "файл.txt",
    "🙂",
    "a//b",
    "a/./b",
    "photos/2015/august/myphoto.jpg",
]


class UnsignedPayloadConfig:
    """The smallest thing `S3SigV4Auth` accepts as a client config with payload signing off."""

    s3 = {"payload_signing_enabled": False}


def uri_encode_key(key):
    """The encoding both the signer and the URL builder must agree on.

    Python's `quote` leaves `A-Za-z0-9_.-~` alone by default; `safe='/~'` adds the slash, which in
    a key separates path segments.
    """
    return quote(key, safe="/~")


def freeze_time():
    botocore.auth.get_current_datetime = lambda: DATE


def write(case_dir, suffix, text):
    os.makedirs(case_dir, exist_ok=True)
    with open(os.path.join(case_dir, suffix), "w", encoding="utf-8") as handle:
        handle.write(text)


def build_url(style, bucket, key, endpoint="s3.us-east-1.amazonaws.com"):
    encoded = uri_encode_key(key)
    if style == "path":
        return f"https://{endpoint}/{bucket}/{encoded}", endpoint
    return f"https://{bucket}.{endpoint}/{encoded}", f"{bucket}.{endpoint}"


def header_case(name, method, style, bucket, key, query="", body=b"", unsigned=False, token=None):
    url, host = build_url(style, bucket, key)
    if query:
        url = f"{url}?{query}"

    request = AWSRequest(method=method, url=url, data=body)
    request.headers["Host"] = host
    if unsigned:
        # `S3SigV4Auth._should_sha256_sign_payload` ignores the plain
        # `context['payload_signing_enabled']` that the generic signer reads: for S3 it looks at
        # the client config first, and without it returns True unless a checksum header is present.
        # So the only way to ask botocore for UNSIGNED-PAYLOAD is through the config object.
        request.context["client_config"] = UnsignedPayloadConfig()

    auth = S3SigV4Auth(Credentials(ACCESS_KEY, SECRET_KEY, token), SERVICE, REGION)
    auth.add_auth(request)

    # add_auth leaves its own Authorization header behind, and canonical_request signs whatever
    # headers it finds. Take it off again to get back the request that was actually signed.
    authorization = request.headers["Authorization"]
    del request.headers["Authorization"]

    canonical = auth.canonical_request(request)
    case_dir = os.path.join(HERE, "header", name)
    write(case_dir, "input", input_text(method, style, bucket, key, query, body, unsigned, token))
    write(case_dir, "creq", canonical)
    write(case_dir, "sts", auth.string_to_sign(request, canonical))
    write(case_dir, "authz", authorization)
    write(case_dir, "sha256", request.headers["X-Amz-Content-SHA256"])


def presign_case(name, method, style, bucket, key, expires, query="", token=None):
    url, host = build_url(style, bucket, key)
    if query:
        url = f"{url}?{query}"

    request = AWSRequest(method=method, url=url)
    request.headers["Host"] = host

    auth = S3SigV4QueryAuth(Credentials(ACCESS_KEY, SECRET_KEY, token), SERVICE, REGION, expires)
    auth.add_auth(request)

    case_dir = os.path.join(HERE, "presign", name)
    write(case_dir, "input", input_text(method, style, bucket, key, query, b"", True, token, expires))
    write(case_dir, "url", request.url)


def input_text(method, style, bucket, key, query, body, unsigned, token, expires=None):
    lines = [
        f"method={method}",
        f"style={style}",
        f"bucket={bucket}",
        f"key={key}",
        f"query={query}",
        f"body={body.decode('utf-8')}",
        f"unsigned={'true' if unsigned else 'false'}",
        f"token={token or ''}",
    ]
    if expires is not None:
        lines.append(f"expires={expires}")
    return "\n".join(lines)


def main():
    freeze_time()

    for subdirectory in ("header", "presign"):
        path = os.path.join(HERE, subdirectory)
        if os.path.isdir(path):
            shutil.rmtree(path)

    write(
        HERE,
        "key-encoding",
        "\n".join(f"{key}\t{uri_encode_key(key)}" for key in KEYS),
    )

    header_case("virtual-hosted-get", "GET", "virtual", "photos", "hello.txt")
    header_case("path-style-get", "GET", "path", "photos", "hello.txt")
    header_case("key-with-space", "GET", "virtual", "photos", "my dir/file.txt")
    header_case("key-with-non-ascii", "GET", "virtual", "photos", "файл.txt")
    header_case("key-outside-basic-plane", "GET", "virtual", "photos", "🙂")
    header_case("key-with-dot-segment", "GET", "virtual", "photos", "a/./b")
    header_case("key-with-repeated-slash", "GET", "virtual", "photos", "a//b")
    header_case("list-objects-v2", "GET", "virtual", "photos", "", query="list-type=2&prefix=a%2Fb")
    header_case("path-style-list", "GET", "path", "photos", "", query="list-type=2")
    header_case("put-with-body", "PUT", "virtual", "photos", "hello.txt", body=b"hello")
    header_case("put-unsigned-payload", "PUT", "virtual", "photos", "hello.txt", unsigned=True)
    header_case("with-session-token", "GET", "virtual", "photos", "hello.txt", token=SESSION_TOKEN)
    header_case("multipart-upload-part", "PUT", "virtual", "photos", "hello.txt", query="partNumber=1&uploadId=abc")

    presign_case("get-default-expiry", "GET", "virtual", "photos", "hello.txt", 3600)
    presign_case("get-path-style", "GET", "path", "photos", "hello.txt", 3600)
    presign_case("get-key-with-space", "GET", "virtual", "photos", "my dir/file.txt", 900)
    presign_case("put-max-expiry", "PUT", "virtual", "photos", "hello.txt", 604800)
    presign_case("get-with-session-token", "GET", "virtual", "photos", "hello.txt", 3600, token=SESSION_TOKEN)
    presign_case("get-with-query", "GET", "virtual", "photos", "hello.txt", 3600, query="versionId=v1")
    presign_case("get-key-with-non-ascii", "GET", "virtual", "photos", "файл.txt", 3600)

    write(HERE, "generated-with.txt", f"botocore {botocore.__version__}\n")


if __name__ == "__main__":
    main()
