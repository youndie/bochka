#!/usr/bin/env python3
"""Reads a pitest report and prints what mutations are run for: the list of survivors.

There is deliberately no survival percentage here. It would measure the **mutation set**, and on
Kotlin half of that set is code the compiler wrote — so the figure moves when somebody drops a
`data class`, not when the tests get stricter.

Hence three buckets rather than one:

* **noise** — a mutation in code that is not in the source. Every rule below is named and prints its
  own count: a rule that swallowed a real mutation has to be visible;
* **uncovered** — no test ever reached this line. That is a map, not a verdict;
* **survived** — a test did reach it and did not notice the change. The only bucket anybody reads.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
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


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: mutation.py <mutations.xml>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.exists():
        print(f"no report at {path}", file=sys.stderr)
        return 2

    mutations = parse(path)
    if not mutations:
        # An empty report is not "nothing to break", it is a run that never happened.
        print(f"{path}: zero mutations — the run did not take place", file=sys.stderr)
        return 1

    noise = Counter()
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
            survived[m["cls"]].append(m)

    n_surv = sum(len(v) for v in survived.values())
    n_unc = sum(len(v) for v in uncovered.values())

    print(f"# {path}")
    print()
    print(f"mutations       {len(mutations)}")
    print(f"  noise         {sum(noise.values())}  (rules below)")
    print(f"  detected      {detected}")
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
