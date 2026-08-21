#!/usr/bin/env python3
"""Did this run measure the server, or the machine it ran on? (M-206)

`ceph/s3-tests` is the one number this project publishes outside its own repository, and it is
taken on whatever machine happens to be free. That machine has been the problem before: a loaded
one produced **228 of 744 instead of 426**, with 176 unclassified failures and dozens of `500`s,
and it took an hour and nearly a wrongly-declared regression to find out that nothing had
regressed. The cases were green again on a quiet machine.

The signal that separates the two is not the score — a bad score looks like a bad score either
way. It is **timeouts**. The suite waits on real time in a dozen places; the longest case here
sleeps ten intervals of five seconds against a `pytest-timeout` of sixty, which is a margin of
twenty percent, and on a loaded machine there is no margin at all. A case that ran out of clock
says nothing about the server, so a run holding even one of them is not a measurement of the
server and must not be quoted as one.

So this prints two facts beside the score — how long the slowest case took, and how close that
came to the ceiling — and it fails the run when the clock ran out on anybody.
"""

import os
import sys
from xml.etree import ElementTree


SELF_TEST_CLEAN = """<testsuites><testsuite name="pytest">
  <testcase name="test_fast" time="0.12"/>
  <testcase name="test_slow" time="50.4"/>
  <testcase name="test_refused" time="0.3"><failure message="AssertionError: 403 != 200">boom</failure></testcase>
</testsuite></testsuites>"""

SELF_TEST_TIMED_OUT = """<testsuites><testsuite name="pytest">
  <testcase name="test_fast" time="0.12"/>
  <testcase name="test_slow" time="60.0"><failure message="Failed: Timeout &gt;60.0s">x</failure></testcase>
</testsuite></testsuites>"""


def self_test():
    """Prove the guard can fire, on this machine, before it is relied on.

    A check that never fires is indistinguishable from a check that cannot, and this repository has
    already shipped both — a classification rule that matched nothing, and a guard placed after the
    line that skipped it. What could silently break this one is not our code: it is the wording
    `pytest-timeout` writes into a failure message. So the two shapes are run through the real
    reader every time the harness starts, and a run that cannot recognise a timeout says so instead
    of scoring.
    """
    import tempfile

    for xml, expected in ((SELF_TEST_CLEAN, 0), (SELF_TEST_TIMED_OUT, 1)):
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as handle:
            handle.write(xml)
            path = handle.name
        got = verdict(path, 60.0, quiet=True)
        os.unlink(path)
        if got != expected:
            print(f"the timeout guard does not work here: expected {expected}, got {got}", file=sys.stderr)
            return 1
    print("the timeout guard answers both ways")
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        return self_test()
    return verdict(sys.argv[1], float(sys.argv[2]) if len(sys.argv) > 2 else 60.0)


def verdict(
    results,
    ceiling,
    quiet=False,
):
    """`0` when this run measured the server, `1` when it measured the machine as well."""
    root = ElementTree.parse(results).getroot()

    slowest_name, slowest_time = "?", 0.0
    timed_out = []
    for case in root.iter("testcase"):
        try:
            elapsed = float(case.get("time", "0"))
        except ValueError:
            elapsed = 0.0
        name = case.get("name", "?")
        if elapsed > slowest_time:
            slowest_name, slowest_time = name, elapsed
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is None:
            continue
        # `pytest-timeout` writes "Failed: Timeout >60.0s" into the message; a socket that gave up
        # says "timed out". Both mean the same thing here: the clock ran out, not the server.
        text = ((problem.get("message") or "") + " " + (problem.text or "")).lower()
        if "timeout" in text or "timed out" in text:
            timed_out.append(name)

    margin = 100.0 * (ceiling - slowest_time) / ceiling if ceiling else 0.0
    say = (lambda *args: None) if quiet else print
    say(
        f"the slowest case was {slowest_name} at {slowest_time:.1f}s, "
        f"{margin:.0f}% under the {ceiling:.0f}s ceiling"
    )

    if not timed_out:
        return 0

    # Named, not counted. "3 timeouts" sends the next reader back to the log; the names say
    # immediately whether the clock ran out on the family that waits on real time or somewhere it
    # has no business running out at all.
    say()
    say(f"{len(timed_out)} case(s) ran out of clock, so this run measured the machine as much as")
    say("the server, and its score is not this project's number:")
    for name in sorted(timed_out)[:10]:
        say(f"    {name}")
    if len(timed_out) > 10:
        say(f"    … and {len(timed_out) - 10} more")
    say()
    say("Re-run it on a quiet machine. A score taken through a timeout has been wrong by 198")
    say("cases before, and looked like a regression rather than like a busy host.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
