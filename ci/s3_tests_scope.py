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
import sys
import xml.etree.ElementTree as ElementTree


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


def classify(name, rules):
    for status, pattern, reason in rules:
        if pattern in name:
            return status, reason
    return "unclassified", "nobody has looked at this one"


def main():
    results, rules_path = sys.argv[1], sys.argv[2]
    failed_out = sys.argv[3] if len(sys.argv) > 3 else ""
    rules = load_rules(rules_path)

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
    order = ["defect", "deferred", "out-of-scope", "unclassified"]
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

    if failed_out:
        with open(failed_out, "w", encoding="utf-8") as handle:
            for name in sorted(failures):
                status, reason = classify(name, rules)
                handle.write(f"{status}\t{name}\t{reason}\t{messages.get(name, '')}\n")
        print(f"  full list: {failed_out}")


if __name__ == "__main__":
    main()
