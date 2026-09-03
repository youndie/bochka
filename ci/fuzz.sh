#!/usr/bin/env bash
# Points a coverage-guided fuzzer at the parsers that read bytes before anything is verified (M38).
#
# A run, not a gate, for the same reason mutation testing is one: its result is a list of findings
# read one at a time, and it takes as long as you give it rather than as long as it needs. The gate
# still runs these targets on every `./gradlew check` — without JAZZER_FUZZ they replay the corpus
# and nothing else, so every input ever saved under `src/test/resources/…Inputs/` is a permanent
# deterministic test. That is where a finding goes to stay found.
#
#   ./ci/fuzz.sh            one minute a target
#   ./ci/fuzz.sh 1800       half an hour a target
#
# **The duration is printed, and that is not decoration.** "We fuzzed and found nothing" and "we did
# not fuzz" produce the same silence, and only the number tells them apart. The same reason the
# mutation report prints the rules that matched nothing.
set -uo pipefail

readonly SECONDS_PER_TARGET=${1:-60}

root=$(cd "$(dirname "$0")/.." && pwd)
targets=0
summarised=no

# A run that fuzzed nothing is a failure, not a clean sheet — a filter that matches no target, a
# module that stopped being built, a rename. This lives in `trap EXIT` because the check that only
# fires when control reaches the bottom of the script is the one that does not fire: an edit in the
# middle of ci/s3-tests.sh once ended the script early, and it exited without printing either a
# number or a complaint.
finish() {
  local status=$?
  if [ "$summarised" = no ]; then
    echo "this run ended without a summary, which is a failure rather than a pass" >&2
    exit 1
  fi
  exit "$status"
}
trap finish EXIT

echo "fuzzing for ${SECONDS_PER_TARGET}s a target"
echo

# Listed from the sources rather than hard-coded here: a target added to the module and not to this
# list would be a target nobody runs, and it would look exactly like a target that found nothing.
# Targets are `<Class>.<method>`, not classes. libFuzzer takes the process over per **method**, so
# a class holding two `@FuzzTest`s fuzzes the first and silently skips the second — which is how
# this script last claimed three targets while running three of the four it had.
#
# `while read` rather than `mapfile`, which is bash 4 and therefore absent from the bash that ships
# with macOS. A harness that only runs where CI runs is a harness nobody runs before pushing.
names=""
while IFS= read -r name; do
  names="${names}${names:+ }${name}"
  targets=$((targets + 1))
done < <(
  find "$root/bochka-fuzz/src/test/kotlin" -name '*.kt' | sort | while IFS= read -r file; do
    awk -v cls="$(basename "$file" .kt)" '
      /^[[:space:]]*@FuzzTest/ {
        # Annotation and function on one line is a shape Kotlin allows and this used to miss.
        if (match($0, /fun [A-Za-z_][A-Za-z0-9_]*/)) {
          print cls "." substr($0, RSTART + 4, RLENGTH - 4)
          want = 0
        } else {
          want = 1
        }
        next
      }
      want && match($0, /fun [A-Za-z_][A-Za-z0-9_]*/) {
        print cls "." substr($0, RSTART + 4, RLENGTH - 4)
        want = 0
      }
    ' "$file"
  done
)

# Every `.kt` in the module rather than every `*FuzzTest.kt`, and then counted a second way. The
# list is derived from the sources, which is right, but it was derived by a **pattern** that fitted
# the files that happened to exist: a target in a class named anything else was invisible, and an
# invisible target looks exactly like one that found nothing. Measured: an eleventh `@FuzzTest` in
# `ByteRangeTargets.kt` left this script saying "10 targets, nothing found".
#
# So the count is taken again, independently, off the annotation itself — only where it opens a
# line, so that a KDoc mentioning `@FuzzTest` in prose is not miscounted — and the two have to
# agree. This is the third time in this milestone that a runner reported work it had not done.
annotated=$(
  find "$root/bochka-fuzz/src/test/kotlin" -name '*.kt' -exec grep -hE '^[[:space:]]*@FuzzTest' {} + | wc -l | tr -d ' '
)
if [ "$targets" -ne "$annotated" ]; then
  printf 'found %d targets but %d @FuzzTest annotations: the discovery below is missing one\n' \
    "$targets" "$annotated" >&2
  exit 1
fi

if [ "$targets" -eq 0 ]; then
  echo "no fuzz targets found under bochka-fuzz/src/test/kotlin" >&2
  summarised=yes
  exit 1
fi

printf 'targets: %s\n\n' "$names"

# One invocation per target, because libFuzzer takes the process over: a JVM fuzzes one target and
# the rest of that run's targets never start. Running them together looked like it worked — the
# build went green and this script said "2 targets" — because the count came from the sources
# rather than from what ran. A harness reporting work it did not do is worse than no harness, so
# the number below is now counted from "Done N runs", which only libFuzzer prints.
fuzzed=0
failed=0
for name in $names; do
  printf -- '--- %s\n' "$name"
  out=$(JAZZER_FUZZ=1 "$root/gradlew" -p "$root" --console=plain --rerun-tasks \
        -Pbochka.fuzzSeconds="$SECONDS_PER_TARGET" \
        :bochka-fuzz:test --tests "*$name" 2>&1)
  status=$?

  runs=$(printf '%s\n' "$out" | grep -E '^Done [0-9]+ runs' | tail -1)
  if [ -z "$runs" ]; then
    echo "$out" | tail -30
    printf 'FAIL    %s did not fuzz at all — no libFuzzer run in its output\n' "$name"
    failed=$((failed + 1))
    continue
  fi

  fuzzed=$((fuzzed + 1))
  if [ $status -eq 0 ]; then
    printf 'PASS    %s: %s\n' "$name" "$runs"
  else
    printf '%s\n' "$out" | grep -E 'FuzzTestFindingException|Caused by:|^\s+at io\.github|Test unit written' | head -12
    printf 'FIND    %s: %s\n' "$name" "$runs"
    failed=$((failed + 1))
  fi
done

echo
# The guard that matters is not "did anything fail" but "did everything actually run". A target that
# silently does not fuzz reports the same silence as a target that fuzzed and found nothing.
if [ "$fuzzed" -ne "$targets" ]; then
  printf 'only %d of %d targets fuzzed, which is a failure rather than a clean run\n' \
    "$fuzzed" "$targets" >&2
  summarised=yes
  exit 1
fi

if [ "$failed" -eq 0 ]; then
  printf '%d targets, %ds each, nothing found\n' "$targets" "$SECONDS_PER_TARGET"
else
  printf '%d of %d targets have a finding above. Its input was written to\n' "$failed" "$targets"
  printf 'bochka-fuzz/build/crash-*; move it to\n'
  printf 'bochka-fuzz/src/test/resources/io/github/youndie/bochka/fuzz/<Target>Inputs/<method>/\n'
  printf 'and the gate runs it from then on.\n'
fi

summarised=yes
[ "$failed" -eq 0 ]
