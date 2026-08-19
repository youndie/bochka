#!/usr/bin/env bash
# M-151: the collector matrix, on a bench that is not a development machine.
#
# One axis per comparison, and the axis is the collector. Within a heap every variant opens the
# **same** live set — the same seed log, the same object count — and digests the same volume of
# garbage, so the only thing that differs between two rows is the collector. The heap is the other
# axis and it is never varied inside a comparison; a table that moved both would have nothing to
# attribute a difference to.
#
# The object count per heap is the number this project *publishes* for it: `maxMemory() * 0.5 /
# 650` read from a JVM running the shipped profile. That is not the same number under every
# collector — `maxMemory()` is a property of the collector (M-152) — so it is probed once under
# SerialGC and then held fixed, and each variant prints its own ceiling beside it.
#
# Gradle is deliberately absent: the classpath is staged by `:bochka-benchmark:measureDist` and
# copied here, because installing a build tool on a bench puts it inside the thing being measured.
#
#   ./gradlew :bochka-benchmark:measureDist
#   scp -r bochka-benchmark/build/measure bench:~/measure
#   scp ci/gc-measure.sh bench:~/
#   ssh bench './gc-measure.sh'
set -uo pipefail

readonly CP=${BOCHKA_MEASURE_CP:-$HOME/measure/*}
readonly HOME_DIR=${BOCHKA_MEASURE_HOME:-$HOME/.bochka-measure}
readonly SEEDS=${BOCHKA_MEASURE_SEEDS:-$HOME/.bochka-seeds}
readonly REPEATS=${BOCHKA_MEASURE_REPEATS:-3}
readonly OUT=${BOCHKA_MEASURE_OUT:-$HOME/gc-results.txt}
# Everything of the shipped profile except the two things this varies.
readonly PROFILE="-XX:ReservedCodeCacheSize=32M -XX:MaxDirectMemorySize=32M -Xss256k -XX:MaxMetaspaceSize=80M"
readonly MAIN=io.github.youndie.bochka.benchmark.Measurements

heaps=${BOCHKA_MEASURE_HEAPS:-512m 2g 4g}
collectors=${BOCHKA_MEASURE_COLLECTORS:-SerialGC ParallelGC G1GC}

mkdir -p "$HOME_DIR" "$SEEDS"

# The published ceiling for a heap: read from a JVM, not computed here, so this script cannot
# disagree with the server about what the number is.
ceiling_of() {
  BOCHKA_MEASURE_DIR=$HOME_DIR java -Xmx"$1" -XX:+UseSerialGC $PROFILE -cp "$CP" "$MAIN" ceiling \
    | sed -n 's/^ceiling \([0-9]*\).*/\1/p'
}

printf 'bochka: what the collector costs when the live set is the index (M-151)\n' | tee "$OUT"
printf '  host        %s cores, %s\n' "$(nproc)" "$(free -h | awk '/^Mem:/{print $2}')" | tee -a "$OUT"
printf '  java        %s\n' "$(java -version 2>&1 | head -1)" | tee -a "$OUT"
printf '  repeats     %s per variant, median kept, spread printed\n\n' "$REPEATS" | tee -a "$OUT"

for heap in $heaps; do
  keys=$(ceiling_of "$heap")
  if [ -z "$keys" ]; then
    printf 'FAILED to read the ceiling for %s — no run made\n' "$heap" | tee -a "$OUT"
    exit 1
  fi
  printf '### heap %s — %s objects, the number this heap publishes\n' "$heap" "$keys" | tee -a "$OUT"
  for gc in $collectors; do
    for run in $(seq 1 "$REPEATS"); do
      printf '\n--- %s %s run %s ---\n' "$heap" "$gc" "$run" | tee -a "$OUT"
      BOCHKA_MEASURE_DIR=$HOME_DIR \
      BOCHKA_MEASURE_SEED=$SEEDS/gc-$keys \
      BOCHKA_MEASURE_KEYS=$keys \
        java -Xmx"$heap" -XX:+Use"$gc" $PROFILE -cp "$CP" "$MAIN" gc 2>&1 | tee -a "$OUT"
    done
  done
done

printf '\n\n== the matrix, medians ==\n' | tee -a "$OUT"
# The median of the repeats, and the spread beside it: a variant that varies by half is not a
# variant with a value, and printing the spread is what makes that visible rather than smoothed.
awk '
  /^RESULT/ {
    split("", f)
    for (i = 2; i <= NF; i++) { split($i, kv, "="); f[kv[1]] = kv[2] }
    key = f["xmx"] "\t" f["collector"]
    n[key]++
    forced[key "\t" n[key]] = f["forced_median"]
    stall[key "\t" n[key]] = f["stall_max"]
    felt[key "\t" n[key]] = f["forced_felt"]
    max_mem[key] = f["max"]; ceil[key] = f["ceiling"]; live[key] = f["live"]
    rss[key] = f["rss"]; conc[key] = f["concurrent"]
    major[key] = f["major"]; alloc[key] = f["alloc"]; load[key] = f["load"]
  }
  END {
    printf "%-7s %-9s %10s %9s %9s %9s %10s %9s %8s %7s\n", \
      "-Xmx", "collector", "maxMemory", "ceiling", "live", "full gc", "felt as", "stall max", "rss", "spread"
    for (key in n) {
      c = n[key]
      for (i = 1; i <= c; i++) { a[i] = forced[key "\t" i] + 0 }
      for (i = 1; i < c; i++) for (j = i + 1; j <= c; j++) if (a[j] < a[i]) { t = a[i]; a[i] = a[j]; a[j] = t }
      med = a[int((c + 1) / 2)]
      spread = (a[1] > 0) ? a[c] / a[1] : 0
      worst = 0
      for (i = 1; i <= c; i++) { if (stall[key "\t" i] + 0 > worst) worst = stall[key "\t" i] + 0 }
      feltmax = 0
      for (i = 1; i <= c; i++) { if (felt[key "\t" i] + 0 > feltmax) feltmax = felt[key "\t" i] + 0 }
      split(key, k, "\t")
      printf "%-7s %-9s %8s M %9s %7s M %8.3f s %8.0f ms %7.0f ms %6s M %6.2fx%s\n", \
        k[1] "M", k[2], max_mem[key], ceil[key], live[key], med, feltmax, worst, rss[key], spread, \
        (conc[key] == "true" ? "   <- cycle, not pause" : "")
    }
  }
' "$OUT" | tee -a "$OUT.table"

printf '\nfull output in %s, table in %s.table\n' "$OUT" "$OUT"
