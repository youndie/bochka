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
# `while read` rather than `mapfile`, which is bash 4 and therefore absent from the bash that ships
# with macOS. A harness that only runs where CI runs is a harness nobody runs before pushing.
names=""
while IFS= read -r name; do
  names="${names}${names:+ }${name}"
  targets=$((targets + 1))
done < <(find "$root/bochka-fuzz/src/test/kotlin" -name '*FuzzTest.kt' -exec basename {} .kt \; | sort)

if [ "$targets" -eq 0 ]; then
  echo "no fuzz targets found under bochka-fuzz/src/test/kotlin" >&2
  summarised=yes
  exit 1
fi

printf 'targets: %s\n\n' "$names"

JAZZER_FUZZ=1 "$root/gradlew" -p "$root" --console=plain --rerun-tasks \
  -Pbochka.fuzzSeconds="$SECONDS_PER_TARGET" \
  :bochka-fuzz:test
status=$?

echo
if [ $status -eq 0 ]; then
  printf '%d targets, %ds each, nothing found\n' "$targets" "$SECONDS_PER_TARGET"
else
  printf '%d targets, %ds each — a finding is above, and its input was written to\n' \
    "$targets" "$SECONDS_PER_TARGET"
  printf 'bochka-fuzz/build/crash-*. Move it to\n'
  printf 'bochka-fuzz/src/test/resources/io/github/youndie/bochka/fuzz/<Target>Inputs/<method>/\n'
  printf 'and it becomes a test the gate runs from then on.\n'
fi

summarised=yes
exit $status
