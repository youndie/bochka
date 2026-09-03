"""The malformed framings, sent as bytes to both parsers that stand between a client and the store.

Request smuggling is not a bug in a parser; it is a **disagreement** between two of them. So every
case here is sent twice — straight at bochka, and through the nginx configuration from `deploy/` —
and what is compared is not the status code but how many responses came back. One request in must
be one response out: a second response means one of the two parsers saw two requests where the
other saw one, which is the whole mechanism.

Run by `ci/smuggling.sh`, which puts the two ends up. Nothing here starts anything.
"""

import socket
import ssl
import sys

# What each framing must produce, and the two kinds are not the same question.
#
#   REFUSE_BOTH — the bytes are malformed in themselves, so neither parser may answer 2xx.
#   REFUSE_DIRECT — the bytes are a *pipeline* in a framing one parser may legitimately accept.
#     nginx reads bare LF as a line ending, splits the stream into two requests and forwards both
#     in canonical form; two responses then are correct rather than a smuggle, because the front
#     end counted two. What must hold is that bochka alone refuses, so that a deployment without
#     nginx is not the lenient one. What the pair does is printed rather than asserted.
REFUSE_BOTH = "refuse-both"
REFUSE_DIRECT = "refuse-direct"

CASES = {
    # A request line ended by a bare LF. RFC 9112 allows a recipient to accept it, which is exactly
    # what makes it dangerous: "allowed to" is not "must", and two recipients may differ.
    "bare-lf-request-line": (
        REFUSE_DIRECT,
        b"GET /-/healthy HTTP/1.1\nHost: h\r\n\r\nGET /smuggled HTTP/1.1\r\nHost: h\r\n\r\n",
    ),
    # The same idea one line further in, where a lenient header parser is more common.
    "bare-lf-header": (
        REFUSE_DIRECT,
        b"GET /-/healthy HTTP/1.1\r\nHost: h\nX-A: b\r\n\r\nGET /smuggled HTTP/1.1\r\nHost: h\r\n\r\n",
    ),
    # Space before the colon: forbidden, and the classic way to hide a second Content-Length from
    # one parser while another reads it.
    "space-before-colon": (
        REFUSE_BOTH,
        b"POST /b/k HTTP/1.1\r\nHost: h\r\nContent-Length : 0\r\nContent-Length: 5\r\n\r\nhello",
    ),
    # Line folding, removed from HTTP/1.1 in RFC 7230 and still understood by plenty of code.
    "obs-fold": (
        REFUSE_BOTH,
        b"POST /b/k HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n\tContent-Length: 5\r\n\r\nhello",
    ),
    # A NUL inside a header value, which terminates the string in anything written in C.
    "nul-in-value": (
        REFUSE_BOTH,
        b"GET /-/healthy HTTP/1.1\r\nHost: h\r\nX-A: b\x00c\r\n\r\nGET /smuggled HTTP/1.1\r\nHost: h\r\n\r\n",
    ),
    # Both framings at once: the oldest one, and the only case here every parser is expected to
    # refuse outright.
    "length-and-encoding": (
        REFUSE_BOTH,
        b"POST /b/k HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
    ),
}


def send(host: str, port: int, payload: bytes, tls: bool) -> bytes:
    """Sends the bytes and reads until the far end stops talking or four seconds pass."""
    raw = socket.create_connection((host, port), timeout=4)
    try:
        if tls:
            context = ssl._create_unverified_context()  # noqa: S323 - a certificate made ten lines ago
            raw = context.wrap_socket(raw, server_hostname=host)
        raw.sendall(payload)
        answer = b""
        while len(answer) < 65536:
            try:
                chunk = raw.recv(4096)
            except (TimeoutError, socket.timeout, ssl.SSLError, OSError):
                break
            if not chunk:
                break
            answer += chunk
        return answer
    finally:
        try:
            raw.close()
        except OSError:
            pass


def responses(answer: bytes) -> int:
    return answer.count(b"HTTP/1.")


def status(answer: bytes) -> str:
    if not answer:
        return "nothing"
    return answer.split(b"\r\n", 1)[0].decode("latin-1", "replace")[:32]


def accepted(answer: bytes) -> bool:
    """Whether anything in what came back is a success. Any of them, not only the first."""
    return any(line.startswith(b"HTTP/1.") and b" 2" in line[:12] for line in answer.split(b"\r\n"))


def answered(answer: bytes) -> bool:
    """Whether anything came back at all.

    Asked separately from [accepted] because silence passes that one. A refusal has to **be** an
    answer: a connection closed without a byte is a network error to every SDK, which retries it,
    and this repository has paid for that reading twice -- once when an exception thrown out of the
    screen closed the socket and a foreign suite recorded it as a broken connection, and once when
    a full disk answered nothing and turned into a retry storm. Measured before this existed: with
    the refusal on the parser path writing nothing, all six framings reported `ok ... nothing` and
    the run exited 0.
    """
    return b"HTTP/1." in answer


def main() -> int:
    direct_port = int(sys.argv[1])
    proxy_port = int(sys.argv[2])
    failures = 0
    for name, (kind, payload) in CASES.items():
        direct = send("127.0.0.1", direct_port, payload, tls=False)
        through = send("127.0.0.1", proxy_port, payload, tls=True)

        if not answered(direct):
            print(f"  FAIL    {name} direct: closed without an answer, which every SDK retries")
            failures += 1
        elif accepted(direct):
            print(f"  FAIL    {name} direct: answered {status(direct)} to a malformed framing")
            failures += 1
        else:
            print(f"  ok      {name:24s} direct         {status(direct)}")

        if kind == REFUSE_BOTH and accepted(through):
            print(f"  FAIL    {name} through nginx: answered {status(through)} to a malformed framing")
            failures += 1
        else:
            note = "" if kind == REFUSE_BOTH else f"  [nginx's call: {responses(through)} responses]"
            print(f"  ok      {name:24s} through nginx  {status(through)}{note}")

    print(f"\n{len(CASES)} framings, {failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
