#!/usr/bin/env bash
# Runs what the Kubernetes operator will do, on one machine, against the built-in mock API:
#   1. setup once        -> writes the shared list to a file
#   2. two workers       -> each takes half the users and half the list; worker 1 waits for a common start time
#   3. merge             -> one HTML report, SLAs judged on all the data
# Usage (from the repo root):  bash scripts/local-distributed-demo.sh
# Workers run one after the other here (they share the mock's port); in Kubernetes they run in parallel.
set -u
cd "$(dirname "$0")/.."
OUT="gatling-api-core/target/dist-demo"
rm -rf "$OUT"; mkdir -p "$OUT/logs"
SIM="-Dgatling.simulationClass=org.codelabuk.perf.it.MockedYamlSimulation"
latest_run() { ls -td gatling-api-core/target/gatling/mockedyamlsimulation-*/ | head -1; }

mvn -B -q -pl gatling-api-core test-compile || exit 1

echo "== 1. setup once"
mvn -B -q -pl gatling-api-core gatling:test $SIM -Dmode=setup "-Dshared.out=$OUT/shared.json" > "$OUT/setup.out" 2>&1 \
  || { echo "setup failed - see $OUT/setup.out"; exit 1; }
grep -E 'setup: wrote' "$OUT/setup.out"

START=$(( $(date +%s) * 1000 + 20000 ))
for i in 0 1; do
  echo "== 2.$((i + 1)) worker $((i + 1)) of 2"
  extra=""; [ "$i" -eq 0 ] && extra="-Dstart.at=$START"
  # a worker's own SLA result is informational only (failOnError=false) - the merged run decides
  mvn -B -q -pl gatling-api-core gatling:test $SIM -Dworker.index=$i -Dworker.count=2 "-Dshared.in=$OUT/shared.json" $extra \
    -Dgatling.failOnError=false > "$OUT/worker$i.out" 2>&1 || { echo "worker $i crashed - see $OUT/worker$i.out"; exit 1; }
  grep -E 'shared.in:|Waiting|> request count' "$OUT/worker$i.out"
  cp "$(latest_run)simulation.log" "$OUT/logs/worker$i.log"
done

echo "== 3. merge"
mvn -B -q -pl gatling-api-core exec:exec@merge "-Dperf.args=--input $OUT/logs --output $OUT/results --name merged" > "$OUT/merge.out" 2>&1
code=$?
grep -E 'Merged|> request count|---- Errors|^> [A-Z].*\(|: (true|false)|RESULT|index.html' "$OUT/merge.out"
exit $code
