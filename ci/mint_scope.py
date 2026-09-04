"""Counts what mint executed, and says why each failure is there.

The shape is `ci/s3_tests_scope.py`'s, and the discipline is the same: a percentage without the
number of cases that ran is a percentage of an unknown, and a failure without a reason is a debt
nobody can tell from a decision. What differs is the suite — `mint` tests MinIO's own extensions
beside S3, so "out of scope" here means "not a thing S3 does" rather than "not built yet".
"""

import json
import sys


def rules(path: str) -> list[tuple[str, str, str]]:
    """Reads `<status> <substring of the case name> <reason>` lines, blank and `#` lines ignored."""
    parsed = []
    with open(path, encoding="utf-8") as file:
        for line in file:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            status, rest = line.split(" ", 1)
            pattern, reason = rest.split(" ", 1)
            if status not in STATUSES:
                # The status is the claim -- somebody else's product, a gap with a task, a wrong
                # answer with a task -- and a word this script does not know is a claim it cannot
                # check. Taking it quietly moves a real failure out of the unexplained bucket, the
                # only one anybody reads: measured with a rule reading `out-of-scop`, which was
                # counted as explained and left the run at rc 0. Refused by name; the rule is
                # dropped, so the failure stays where it was.
                print(
                    f"{path}: '{status}' is not a status this script enforces "
                    f"({', '.join(sorted(STATUSES))}); the rule is ignored: {line}",
                    file=sys.stderr,
                )
                continue
            parsed.append((status, pattern, reason))
    return parsed


# The three words the file's own header defines, and the only ones a rule may carry.
STATUSES = frozenset({"out-of-scope", "deferred", "defect"})


def entries(path: str) -> list[dict]:
    """Reads mint's log, which is a stream of JSON documents rather than one per line.

    Some suites write a compact object per line and others pretty-print across a dozen lines, in
    the same file. A line-based reader counts the first kind and silently drops the second — which
    is how a run with twelve failures reports two.
    """
    text = open(path, encoding="utf-8").read()
    decoder = json.JSONDecoder()
    found, at = [], 0
    while True:
        while at < len(text) and text[at] in " \t\r\n":
            at += 1
        if at >= len(text):
            return found
        try:
            value, at = decoder.raw_decode(text, at)
        except json.JSONDecodeError:
            # Not everything in there is a document; skip to the next line and carry on rather than
            # stopping, because stopping would lose every result after the first stray byte.
            newline = text.find("\n", at)
            if newline < 0:
                return found
            at = newline + 1
            continue
        if isinstance(value, dict):
            found.append(value)


def main() -> int:
    results = entries(sys.argv[1])

    scope = rules(sys.argv[2])
    passed = [r["name"] for r in results if r.get("status") == "PASS"]
    skipped = [r["name"] for r in results if r.get("status") == "NA"]
    failed = [r for r in results if r.get("status") == "FAIL"]

    print(f"mint: {len(passed)} of {len(results)} passed, {len(skipped)} not applicable, {len(failed)} failed")
    print()

    def describes(failure: dict) -> str:
        """Everything a rule may match on.

        The name alone is not enough and that is mint's doing: half the suites report every case
        under the SDK's own name and put the case in `function`, so a rule matched on the name
        either explains one thing or thirteen. The message is in here too, because two suites name
        nothing at all and only their text says which case it was.

        `args` is in here for one reason, and it is the reason this paragraph exists: two minio-js
        failures say only "listObjects lists 3 objects, expected 0", and what tells them apart from
        any other listing failure is the bucket the suite made -- `minio-js-fd-…`, its own prefix
        for the force-deletion cases. Without it the only rule that can explain them is one
        matching `listObjects`, which is a rule about a method rather than about a case; and a rule
        that broad cannot be reported stale, because it goes on matching after its subject has been
        fixed. That is not hypothetical: `aws-sdk-go` and `versioning` -- two suite names -- kept
        explaining failures for two releases after the defects they named were closed.
        """
        parts = (
            failure.get("name"),
            failure.get("function"),
            failure.get("args"),
            failure.get("message"),
            failure.get("error"),
        )
        # Lowercased, because the same case appears as `uploadSnowballObjects` in one SDK and
        # `test_upload_snowball_objects` in another, and a rule that depends on somebody else's
        # naming convention explains one of the two and reads as if it explained both.
        return " ".join(str(part) for part in parts if part).lower()

    unexplained = []
    for failure in failed:
        name = describes(failure)
        match = next(((s, p, r) for s, p, r in scope if p.lower() in name), None)
        if match is None:
            unexplained.append(failure)
            print(f"  UNCLASSIFIED  {failure.get('name')}: {str(failure.get('function'))[:70]}")
        else:
            print(f"  {match[0]:<12}  {failure.get('name')} / {str(failure.get('function'))[:44]}")

    # A rule that matches nothing is a claim about a failure that no longer happens, and it reads
    # as true to whoever comes next. The same guard `ci/s3-tests.sh` grew for the same reason.
    stale = [(p, r) for _, p, r in scope if not any(p.lower() in describes(f) for f in failed)]
    for pattern, reason in stale:
        print(f"  STALE         no failure matches '{pattern}' any more — {reason}")

    print()
    if unexplained:
        print(f"{len(unexplained)} failures with no reason on file: classify them or fix them")
        return 1
    if stale:
        print(f"{len(stale)} rules explain nothing: they describe a failure that has stopped happening")
        return 1
    if not results:
        print("mint produced no results at all")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
