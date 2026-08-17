#!/usr/bin/env python3
"""Produce `aws-chunked` bodies with an implementation that is not ours.

Nobody publishes vectors for the streaming upload framing — not AWS, not anyone else — and it is
the one part of the input path where being wrong means ``aws s3 cp`` does not work at all. So the
fixtures are generated here, in another language, from the two sources that describe the format:

* the frame layout and the chunk-signature chain, as implemented in the reference server
  (``minio/minio``, ``cmd/streaming-signature-v4.go``): each chunk is
  ``<hex size>;chunk-signature=<hex>\\r\\n<data>\\r\\n``, and the string to sign is

      AWS4-HMAC-SHA256-PAYLOAD \\n <timestamp> \\n <scope> \\n <previous signature> \\n
      <sha256 of the empty string> \\n <sha256 of this chunk>

  while the trailer signature uses ``AWS4-HMAC-SHA256-TRAILER`` and has **no** empty-payload line —
  five lines against four;
* the unsigned form, as botocore emits it (``botocore/httpchecksum.py``, ``AwsChunkedWrapper``):
  ``<hex size>\\r\\n<data>\\r\\n`` per chunk and ``0\\r\\n<name>:<base64>\\r\\n\\r\\n`` at the end.

This file calls nothing from bochka. Agreeing with it therefore checks the reading of the format
rather than its repetition — the same reason the key-order vectors come out of Python's ``sorted``.

Regenerate in place (the output is committed; the tests do not run Python):

    python3 docs/spec/aws-chunked/generate.py
"""

import base64
import hashlib
import hmac
import zlib
from pathlib import Path

SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
ACCESS_KEY = "AKIDEXAMPLE"
TIMESTAMP = "20150830T123600Z"
DATE = "20150830"
REGION = "us-east-1"
SERVICE = "s3"
# The signature of the request itself: the chain hangs off it, which is why the body cannot be
# checked before the headers are. Any fixed value does as a seed for a fixture.
SEED = "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"

EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
SCOPE = f"{DATE}/{REGION}/{SERVICE}/aws4_request"


def signing_key():
    key = ("AWS4" + SECRET).encode()
    for part in (DATE, REGION, SERVICE, "aws4_request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    return key


def sign(string_to_sign):
    return hmac.new(signing_key(), string_to_sign.encode(), hashlib.sha256).hexdigest()


def chunk_signature(previous, data):
    return sign(
        "AWS4-HMAC-SHA256-PAYLOAD\n"
        f"{TIMESTAMP}\n{SCOPE}\n{previous}\n{EMPTY_SHA256}\n"
        + hashlib.sha256(data).hexdigest()
    )


def trailer_signature(previous, trailer_bytes):
    return sign(
        "AWS4-HMAC-SHA256-TRAILER\n"
        f"{TIMESTAMP}\n{SCOPE}\n{previous}\n" + hashlib.sha256(trailer_bytes).hexdigest()
    )


def crc32_base64(data):
    return base64.b64encode(zlib.crc32(data).to_bytes(4, "big")).decode()


def signed_chunks(chunks, trailer=None):
    """The STREAMING-AWS4-HMAC-SHA256-PAYLOAD framing, with or without a signed trailer."""
    out = bytearray()
    previous = SEED
    for data in chunks:
        previous = chunk_signature(previous, data)
        out += f"{len(data):x};chunk-signature={previous}\r\n".encode()
        out += data + b"\r\n"

    # The final zero-sized chunk. It still carries a signature, and what follows it is either the
    # blank line that ends the body or the trailers — but *not* both: unlike a data chunk, it has no
    # trailing CRLF of its own. The reference server reads the trailer immediately after this line
    # (`cmd/streaming-signature-v4.go`, readTrailers).
    previous = chunk_signature(previous, b"")
    out += f"0;chunk-signature={previous}\r\n".encode()
    if trailer is None:
        out += b"\r\n"
    if trailer is not None:
        name, value = trailer
        line = f"{name}:{value}".encode()
        out += line + b"\r\n"
        # What is hashed is the line terminated by a bare \n, not the \r\n it travelled with.
        signature = trailer_signature(previous, line + b"\n")
        out += f"x-amz-trailer-signature:{signature}\r\n".encode()
        out += b"\r\n"
    return bytes(out)


def unsigned_trailer(chunks, trailer):
    """The STREAMING-UNSIGNED-PAYLOAD-TRAILER framing, as botocore writes it."""
    out = bytearray()
    for data in chunks:
        out += f"{len(data):x}\r\n".encode() + data + b"\r\n"
    name, value = trailer
    out += b"0\r\n" + f"{name}:{value}".encode() + b"\r\n\r\n"
    return bytes(out)


def write(name, mode, body, obj, trailers=""):
    directory = Path(__file__).parent / name
    directory.mkdir(exist_ok=True)
    (directory / "body").write_bytes(body)
    (directory / "object").write_bytes(obj)
    (directory / "meta").write_text(
        "\n".join(
            [
                f"mode={mode}",
                f"access-key={ACCESS_KEY}",
                f"secret={SECRET}",
                f"timestamp={TIMESTAMP}",
                f"date={DATE}",
                f"region={REGION}",
                f"seed-signature={SEED}",
                f"decoded-length={len(obj)}",
                f"trailers={trailers}",
                "",
            ]
        )
    )
    print(f"{name}: {len(body)} bytes of framing around {len(obj)} bytes of object")


def main():
    small = [b"hello world"]
    several = [b"a" * 1024, b"b" * 700, b"c" * 3]
    # 64 KiB is the chunk size the AWS SDKs use, so a body that crosses it exercises the state
    # machine the way a real upload does rather than the way a unit test would.
    realistic = [bytes([i % 251]) * 65536 for i in range(3)] + [b"tail"]

    write("signed-one-chunk", "signed", signed_chunks(small), b"".join(small))
    write("signed-several-chunks", "signed", signed_chunks(several), b"".join(several))
    write("signed-64k-chunks", "signed", signed_chunks(realistic), b"".join(realistic))
    write("signed-empty-object", "signed", signed_chunks([]), b"")

    obj = b"".join(several)
    write(
        "signed-trailer-crc32",
        "signed-trailer",
        signed_chunks(several, ("x-amz-checksum-crc32", crc32_base64(obj))),
        obj,
        trailers="x-amz-checksum-crc32",
    )
    write(
        "unsigned-trailer-crc32",
        "unsigned-trailer",
        unsigned_trailer(several, ("x-amz-checksum-crc32", crc32_base64(obj))),
        obj,
        trailers="x-amz-checksum-crc32",
    )


if __name__ == "__main__":
    main()
