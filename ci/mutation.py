#!/usr/bin/env python3
"""Reads a pitest report and prints what mutations are run for: the list of survivors.

There is deliberately no survival percentage here. It would measure the **mutation set**, and on
Kotlin half of that set is code the compiler wrote — so the figure moves when somebody drops a
`data class`, not when the tests get stricter.

Hence four buckets rather than one:

* **noise** — a mutation in code that is not in the source. Every rule below is named and prints its
  own count: a rule that swallowed a real mutation has to be visible;
* **classified** — a survivor somebody has read and explained in `ci/mutation-scope.txt`: a change
  that cannot be observed, or one observable only from outside a JVM. The reason is in the file
  beside the rule, so the judgement can be argued with rather than inherited;
* **uncovered** — no test ever reached this line. That is a map, not a verdict;
* **survived** — a test did reach it, it is not explained, and it went unnoticed. The only bucket
  anybody reads.

The scope file is the same shape as `ci/s3-tests-scope.txt` and carries the same guarantee: a rule
that matches nothing says so out loud. A rule that cannot fire has been found in this repository
three times, and each time it had been quietly holding a line in the accounting.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

# A rule is (name, predicate). The name is printed with its count, so there can be no bucket called
# "other" here: a bucket without a name is where a lost mutation would never be found.
NOISE_RULES: list[tuple[str, object]] = [
    (
        "compiler null check (Intrinsics)",
        lambda m: "kotlin/jvm/internal/Intrinsics" in m["desc"],
    ),
    (
        "coroutine resumption (ResultKt::throwOnFailure, Continuation::resumeWith)",
        lambda m: "kotlin/ResultKt::throwOnFailure" in m["desc"]
        or "kotlin/coroutines/Continuation::resumeWith" in m["desc"],
    ),
    (
        "the COROUTINE_SUSPENDED marker of a suspend function",
        lambda m: m["desc"].startswith("replaced return value with null")
        and "Lkotlin/coroutines/Continuation;" in m["signature"],
    ),
    (
        # A builder lambda returns Unit and its value is discarded by the caller. Replacing it with
        # null is unobservable by construction rather than because a test is missing.
        "the Unit value of a lambda nobody reads",
        lambda m: m["desc"].startswith("replaced return value with null")
        and m["signature"].endswith("Lkotlin/Unit;"),
    ),
    (
        "a generated data class member (equals/hashCode/toString/copy/componentN)",
        lambda m: m["method"] in ("equals", "hashCode", "toString", "copy", "copy$default")
        or re.fullmatch(r"component\d+", m["method"]) is not None,
    ),
    (
        "a synthetic compiler method (accessor, bridge, lambda)",
        lambda m: m["method"].startswith("access$")
        or m["method"].endswith("$lambda")
        or "$default" in m["method"],
    ),
]

# pitest counts these statuses as detected, and it is right to: a mutation that hung the process or
# ate the heap is distinguishable from the original code, which is the whole question. Named
# separately because they read differently: a TIMED_OUT inside a loop often means the mutation broke
# the exit rather than that a test caught it.
DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}


def staleness(report: Path) -> str | None:
    """A complaint when the report is older than the code it claims to describe.

    A report from the previous run reads exactly like a fresh one — same shape, same counts, and
    every verdict in it belongs to code that has since changed. M37 lost twenty minutes to this:
    the wait was written as `test -s mutations.xml`, last run's file was already there and not
    empty, so the loop came back at once and four new tests appeared to have killed nothing.

    Timestamps rather than a hash, because the question is only ever "did this run happen after I
    edited": a source newer than the report answers it whatever the contents.
    """
    module = next((parent for parent in report.parents if parent.name and (parent / "src").is_dir()), None)
    if module is None:
        return None
    written = report.stat().st_mtime
    newer = [
        source
        for source in (module / "src").rglob("*.kt")
        if source.stat().st_mtime > written
    ]
    if not newer:
        return None
    when = datetime.fromtimestamp(written).isoformat(timespec="seconds")
    names = ", ".join(sorted(str(source.relative_to(module)) for source in newer)[:3])
    return (
        f"{report}: written {when}, and {len(newer)} source file(s) under {module.name} have "
        f"changed since ({names}). This report describes code that is no longer there — "
        f"run the mutation task again before reading it."
    )


def parse(path: Path) -> list[dict]:
    root = ET.parse(path).getroot()
    out = []
    for m in root:
        out.append(
            {
                "status": m.get("status") or "",
                "cls": m.findtext("mutatedClass") or "",
                "method": m.findtext("mutatedMethod") or "",
                "signature": m.findtext("methodDescription") or "",
                "line": int(m.findtext("lineNumber") or 0),
                "desc": m.findtext("description") or "",
                "file": m.findtext("sourceFile") or "",
            }
        )
    return out


# What a rule may claim. Two words, because there are two ways a survivor can be explained without
# a missing test, and anything else is a judgement this script cannot check.
STATUSES = frozenset({"equivalent", "unobservable"})


def read_scope(near: Path) -> list[tuple[str, str, str]]:
    """The rules explaining survivors, if the file is where it usually is.

    Looked up relative to the repository rather than to the report, because a report lives under
    `build/` and the explanation of a survivor belongs with the code. Absent file means no rules,
    not an error: the report is still a report without them.
    """
    for candidate in (Path("ci/mutation-scope.txt"), near / ".." / "ci" / "mutation-scope.txt"):
        if candidate.exists():
            rules = []
            for line in candidate.read_text().splitlines():
                if not line.strip() or line.lstrip().startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) < 3:
                    print(f"{candidate}: a rule needs three tab-separated columns: {line}", file=sys.stderr)
                    continue
                status = parts[0].strip()
                if status not in STATUSES:
                    # The status is the whole claim: `equivalent` says no test could see it,
                    # `unobservable` says another instrument holds it. A word this script does not
                    # know is a claim it cannot check, and accepting one quietly moves a real
                    # survivor out of the only bucket anybody reads -- measured with a rule reading
                    # `wontfix / looks harmless to me`, which the report counted as classified
                    # without a word. Refused by name, and the mutation stays where it was.
                    print(
                        f"{candidate}: '{status}' is not a status this script enforces "
                        f"({', '.join(sorted(STATUSES))}); the rule is ignored: {line}",
                        file=sys.stderr,
                    )
                    continue
                rules.append((status, parts[1].strip(), parts[2].strip()))
            return rules
    return []


def classify(mutation: dict, scope: list[tuple[str, str, str]]) -> str | None:
    """The first rule that matches, as `status<TAB>pattern`, or None.

    The pattern is matched against `<file>:<method>:<description>` — the same shape the suite's
    scope file uses, and for the same reason: a line number moves with the next edit, and a rule
    pinned to one would explain a different mutation a week later without saying so.

    A plain substring, with one exception: `*` stands for "anything up to the next colon", so a
    claim about every method of one file can be written as one rule instead of nine. Deliberately
    not a general expression — a rule whose reach nobody can read is a rule nobody will argue with.
    """
    subject = f"{mutation['file']}:{mutation['method']}:{mutation['desc']}"
    for status, pattern, _ in scope:
        if "*" in pattern:
            expression = ".*?".join(re.escape(part) for part in pattern.split("*"))
            if re.search(expression, subject):
                return f"{status}\t{pattern}"
        elif pattern in subject:
            return f"{status}\t{pattern}"
    return None


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: mutation.py <mutations.xml>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.exists():
        print(f"no report at {path}", file=sys.stderr)
        return 2

    stale = staleness(path)
    if stale:
        print(stale, file=sys.stderr)

    mutations = parse(path)
    if not mutations:
        # An empty report is not "nothing to break", it is a run that never happened.
        print(f"{path}: zero mutations — the run did not take place", file=sys.stderr)
        return 1

    scope = read_scope(path.parent)
    noise = Counter()
    classified = Counter()
    survived: dict[str, list[dict]] = defaultdict(list)
    uncovered: dict[str, list[dict]] = defaultdict(list)
    detected = 0

    for m in mutations:
        rule = next((name for name, hit in NOISE_RULES if hit(m)), None)
        if rule is not None:
            noise[rule] += 1
            continue
        if m["status"] in DETECTED:
            detected += 1
        elif m["status"] == "NO_COVERAGE":
            uncovered[m["cls"]].append(m)
        else:
            explained = classify(m, scope)
            if explained is not None:
                classified[explained] += 1
            else:
                survived[m["cls"]].append(m)

    n_surv = sum(len(v) for v in survived.values())
    n_unc = sum(len(v) for v in uncovered.values())

    print(f"# {path}")
    print()
    print(f"mutations       {len(mutations)}")
    print(f"  noise         {sum(noise.values())}  (rules below)")
    print(f"  detected      {detected}")
    print(f"  classified    {sum(classified.values())}  (ci/mutation-scope.txt)")
    print(f"  uncovered     {n_unc}  <- no test ever reached it")
    print(f"  SURVIVED      {n_surv}  <- read one by one")
    print()

    print("noise by rule:")
    for name, count in noise.most_common():
        print(f"  {count:5}  {name}")
    for name, _ in NOISE_RULES:
        if name not in noise:
            print(f"  {0:5}  {name}  <- this rule matched nothing")
    print()

    if scope:
        # Zero is printed as loudly as anything else, and one zero is ordinary: a rule about another
        # module matches nothing in this module's report. The one worth looking at is a rule about
        # **this** module that matched nothing — either it was written for a line that moved, or the
        # mutations it explains are now uncovered rather than surviving.
        print("classified survivors, by rule:")
        for status, pattern, reason in scope:
            key = f"{status}\t{pattern}"
            count = classified.get(key, 0)
            dead = "  <- this rule matched nothing" if count == 0 else ""
            print(f"  {count:5}  [{status}] {pattern}: {reason}{dead}")
        print()

    for title, bucket in (("SURVIVED", survived), ("UNCOVERED", uncovered)):
        if not bucket:
            continue
        print(f"## {title}")
        for cls in sorted(bucket, key=lambda c: -len(bucket[c])):
            items = bucket[cls]
            print(f"\n{cls}  ({len(items)})")
            for m in sorted(items, key=lambda x: x["line"]):
                print(f"  {m['file']}:{m['line']:<5} {m['method']:<28} {m['desc']}")
        print()

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BrokenPipeError:
        # `| head` closes the pipe, and a traceback at that point reads as a broken report.
        sys.stdout = None
        raise SystemExit(0)
