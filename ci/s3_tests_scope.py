#!/usr/bin/env python3
"""Groups the ceph/s3-tests failures by why they fail.

A percentage says how far there is to go and nothing about what the distance is made of. The
difference between "bochka has no versioning and never will" and "bochka has a defect here" is the
whole of that distance, and only one of the two is worth working on.

    python3 ci/s3_tests_scope.py <results.xml> <scope-rules> [failed-out]

The rules file is `ci/s3-tests-scope.txt`. Anything it does not match is reported as
`unclassified` — deliberately loud, because a failure nobody has looked at must not be able to
hide inside a category by accident.
"""

import collections
import re
import sys
import xml.etree.ElementTree as ElementTree


# A `defect` line says bochka should pass a case and does not, so it owes the task that will make
# it pass -- the file's own header says every one of them names it. Prose satisfied that by eye and
# nothing else: the reference sat inside the sentence, which reads fine and cannot be asked a
# question. This is the same reference as a token the first word can be matched against.
#
# `deferred` deliberately carries none. The header defines it as "in scope eventually, nobody has
# claimed it", so requiring a task there would contradict the status: a deferred line with an owner
# is a `defect` line.
TASK = re.compile(r"^M-\d+\b")


def load_rules(path):
    rules = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            status, pattern, reason = parts[0].strip(), parts[1].strip(), parts[2].strip()
            rules.append((status, pattern, reason))
    return rules


CLOSED = re.compile(r"^\s*- \[x\]\s+\*\*(M-\d+)\*\*")


def closed_tasks(path):
    """Tasks the backlog marks `[x]`, by name.

    Read from the backlog rather than passed in, because the claim being checked is the backlog's:
    a `defect` line says a task will make the case pass, and a closed task says it has. Those two
    sentences live in different files and nothing until now compared them.
    """
    if not path:
        return set()
    found = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            hit = CLOSED.match(line)
            if hit:
                found.add(hit.group(1))
    return found


def unattributed(rules):
    """`defect` rules that name no task, as (pattern, reason) pairs.

    Read from the **rules** rather than from the failures, and that is the whole point rather than
    an implementation detail. A `defect` line placed above a broad one for a family that already
    passes is a sentinel: it matches nothing until something regresses, so anything derived from
    matched cases cannot see it at all. The line that is hardest to notice is exactly the line this
    check exists for.
    """
    return [(pattern, reason) for status, pattern, reason in rules if status == "defect" and not TASK.match(reason)]


def classify(name, rules):
    for status, pattern, reason in rules:
        if pattern in name:
            return status, reason
    return "unclassified", "nobody has looked at this one"


def main():
    results, rules_path = sys.argv[1], sys.argv[2]
    failed_out = sys.argv[3] if len(sys.argv) > 3 else ""
    backlog = sys.argv[4] if len(sys.argv) > 4 else ""
    rules = load_rules(rules_path)
    closed = closed_tasks(backlog)

    failures = []
    messages = {}
    for case in ElementTree.parse(results).getroot().iter("testcase"):
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is not None:
            name = case.get("name", "?")
            failures.append(name)
            messages[name] = (problem.get("message") or "").strip().replace("\n", " ")[:200]

    grouped = collections.defaultdict(lambda: collections.defaultdict(list))
    for name in sorted(failures):
        status, reason = classify(name, rules)
        grouped[status][reason].append(name)

    print("why the rest fail:")
    order = ["defect", "deferred", "off-by-default", "out-of-scope", "unclassified"]
    for status in order + [s for s in grouped if s not in order]:
        if status not in grouped:
            continue
        total = sum(len(names) for names in grouped[status].values())
        print(f"  {status}: {total}")
        for reason, names in sorted(grouped[status].items(), key=lambda item: -len(item[1])):
            print(f"      {len(names):4d}  {reason}")

    unclassified = grouped.get("unclassified", {})
    if unclassified:
        for names in unclassified.values():
            for name in names[:20]:
                print(f"        ? {name}")

    # Printed by name, the same way `unclassified` is, and for the same reason: a rule that claims
    # bochka has a defect without saying who is fixing it is a claim nobody can follow up.
    orphans = unattributed(rules)
    if orphans:
        print(f"  unattributed: {len(orphans)}")
        for pattern, reason in orphans:
            print(f"        ? defect rule '{pattern}' names no task: {reason[:80]}")

    # A `defect` line names the task that will make the case pass; a closed task says it has. When
    # both are true at once the backlog is wrong, and this is the only place the two sentences meet
    # (M-260). It is what turns "declared done because the total went up" from something to be
    # ashamed of into something that cannot happen: the total is not consulted at all here, one
    # named case is.
    regressions = []
    for status, pattern, reason in rules:
        if status != "defect":
            continue
        task = TASK.match(reason)
        if not task or task.group(0) not in closed:
            continue
        hit = [name for name in failures if pattern in name and classify(name, rules)[1] == reason]
        if hit:
            regressions.append((task.group(0), pattern, hit))

    if regressions:
        print(f"  closed-and-failing: {len(regressions)}")
        for task, pattern, names in regressions:
            print(f"        ! {task} is closed and '{pattern}' still fails: {len(names)} case(s)")
            for name in names[:5]:
                print(f"            {name}")

    if failed_out:
        with open(failed_out, "w", encoding="utf-8") as handle:
            for name in sorted(failures):
                status, reason = classify(name, rules)
                handle.write(f"{status}\t{name}\t{reason}\t{messages.get(name, '')}\n")
        print(f"  full list: {failed_out}")


if __name__ == "__main__":
    main()
