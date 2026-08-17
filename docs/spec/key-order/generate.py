#!/usr/bin/env python3
"""Generate the expected ordering of object keys with an implementation that is not ours.

S3 returns objects "in lexicographical order based on their key names"
(``s3-service-2.json``, ListObjectsV2, "Sorting order of returned objects"). A key is a byte
string, so the order is the unsigned byte order — and on the JVM neither obvious way of getting
there is right by default: ``String.compareTo`` compares UTF-16 code units, and ``Byte`` is signed,
so anything above 0x7F sorts before ASCII.

This file exists so that the expectation comes from somewhere other than our reading of the rule.
Python's ``sorted()`` over ``bytes`` is unsigned lexicographic; agreeing with it checks the reading,
not its repetition. Same idea as the signing vectors next door, which come out of botocore.

Regenerate in place (the output is committed, the test does not run Python):

    python3 docs/spec/key-order/generate.py

The seed is fixed, so a regeneration that changes ``vectors.txt`` means this file changed — which
is a diff worth reading rather than accepting.
"""

import random
from pathlib import Path

SEED = 20260817
COUNT = 512

# Cases picked by hand, each for a reason. Random strings alone would miss most of them: they are
# rare in a uniform sample and they are exactly where an implementation goes wrong.
HANDPICKED = [
    # A key is at least one byte; these are the boundaries of "one key is a prefix of another",
    # which is also how CommonPrefixes grouping will have to behave later.
    b"a",
    b"ab",
    b"a/",
    b"a/b",
    b"a0",
    b"a\x00",
    b"a\xff",
    # The divergence between UTF-16 code-unit order and UTF-8 byte order. U+FF01 is one UTF-16
    # unit, U+1F600 is a surrogate pair, and the two orders disagree about them.
    "！".encode(),
    "\U0001f600".encode(),
    # High bytes: a signed byte comparison puts every one of these before "a".
    b"\x7f",
    b"\x80",
    b"\xc0",
    b"\xfe",
    b"\xff",
    b"\xff\x00",
    # Not valid UTF-8 at all. S3 keys are bytes, so this has to sort rather than throw.
    b"\xc3\x28",
    b"\xed\xa0\x80",
    # The same text in composed and decomposed form: different keys, and a filesystem that
    # normalises names would make them one file (research, §1.3).
    "café.txt".encode(),
    "café.txt".encode(),
    # Case: two distinct keys that a case-insensitive filesystem folds together.
    b"Photo.JPG",
    b"photo.jpg",
    # Characters that mean something in a URL or in XML and must not be treated specially here.
    b"a b",
    b"a+b",
    b"a%20b",
    b"a&b",
    b"a<b",
    b"\x01",
]


def random_keys(rng, count):
    """A mixture, because a uniform one only ever exercises one branch of a comparator."""
    alphabets = [
        bytes(range(0x20, 0x7F)),               # printable ASCII
        bytes(range(0x00, 0x100)),              # every byte, valid UTF-8 or not
        "абвгдеёжз/-_.~".encode(),              # multi-byte UTF-8
        b"ab/",                                 # a tiny alphabet, so prefixes collide often
    ]
    keys = []
    for _ in range(count):
        alphabet = rng.choice(alphabets)
        length = rng.randint(1, 40)
        keys.append(bytes(rng.choice(alphabet) for _ in range(length)))
    return keys


def main():
    rng = random.Random(SEED)
    keys = set(HANDPICKED) | set(random_keys(rng, COUNT))

    # The whole point of the file: sorted by an implementation that is not the one under test.
    ordered = sorted(keys)

    out = Path(__file__).with_name("vectors.txt")
    with out.open("w", encoding="ascii") as f:
        f.write("# Object keys in the order S3 lists them: unsigned byte order.\n")
        f.write("# One key per line, hex-encoded, because a key is bytes and need not be text.\n")
        f.write(f"# Produced by generate.py (seed {SEED}) using Python's sorted() over bytes.\n")
        for key in ordered:
            f.write(key.hex())
            f.write("\n")
    print(f"{len(ordered)} keys -> {out}")


if __name__ == "__main__":
    main()
