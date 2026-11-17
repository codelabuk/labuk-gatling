#!/usr/bin/env bash
# End-to-end test of the whole stack on minikube:
#   images -> Helm-installed operator -> LoadTest (2 workers, SR flow against the in-cluster mock API)
#   -> Validating -> Setup -> Running -> Aggregating -> Completed, then copies the merged report back.
#
# Usage (repo root, after `mvn install`):   bash scripts/minikube-e2e.sh
#   SKIP_IMAGES=1   reuse images already loaded into minikube
# Uses `--context minikube` everywhere; your current kubectl context is not changed.
set -u
cd "$(dirname "$0")/.."
K="kubectl --context minikube"
NS=perf-e2e
VERSION=1.0-SNAPSHOT
RUN="sr-flow-$(date +%H%M%S)"
OUT="loadtest-operator/target/e2e"
mkdir -p "$OUT"

if [ -z "${SKIP_IMAGES:-}" ]; then
  echo "== images (docker build -> minikube)"
  # Dockerfile path: Jib's own registry client was very slow behind Docker Desktop's proxy on the dev laptop
  mvn -B -q -pl gatling-api-core test-compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib || exit 1
  docker build -q -t labuk-perf-runner:$VERSION gatling-runner >/dev/null || exit 1
  docker build -q -t labuk-loadtest-operator:$VERSION loadtest-operator >/dev/null || exit 1
  docker build -q -t labuk-mock-api:$VERSION -f gatling-api-core/src/test/docker/Dockerfile gatling-api-core/target >/dev/null || exit 1
  for i in labuk-perf-runner labuk-loadtest-operator labuk-mock-api; do
    # straight into minikube's container runtime (docker driver); `minikube image load` needs wmic,
    # which newer Windows 11 versions no longer have
    docker save "$i:$VERSION" | docker exec -i minikube docker load >/dev/null || minikube image load "$i:$VERSION" --overwrite=true || exit 1
  done
fi

echo "== operator (Helm)"
helm --kube-context minikube upgrade --install loadtest-operator charts/loadtest-operator \
  -n perf-system --create-namespace --wait --timeout 3m || exit 1
$K apply -f charts/loadtest-operator/crds/ >/dev/null   # Helm never upgrades CRDs - keep it current

echo "== system under test + results volume"
$K apply -f loadtest-operator/e2e/cluster.yaml >/dev/null
$K -n $NS rollout status deploy/mock-api --timeout=3m || exit 1

echo "== tests as a ConfigMap (flat: plan + API catalog + users)"
T="$OUT/tests"; rm -rf "$T"; mkdir -p "$T"
P=gatling-api-core/src/test/resources/plans
sed -e 's#include: \[ apis/sr.yaml \]#include: [ sr.yaml ]#' -e 's#file: data/users.csv#file: users.csv#' "$P/sr-email-status.yaml" > "$T/sr-email-status.yaml"
cp "$P/apis/sr.yaml" "$P/data/users.csv" "$T/"
$K -n $NS create configmap sr-tests --from-file="$T" --dry-run=client -o yaml | $K apply -f - >/dev/null

echo "== LoadTest $RUN"
cat <<EOF | $K apply -f -
apiVersion: perf.codelabuk.org/v1alpha1
kind: LoadTest
metadata: { name: $RUN, namespace: $NS }
spec:
  image: labuk-perf-runner:$VERSION
  plan: { configMap: sr-tests, file: sr-email-status.yaml }
  workers: 2
  env: { BASE_URL: "http://mock-api.$NS.svc:18080" }
  resources: { requests: { cpu: 500m, memory: 1Gi }, limits: { memory: 1536Mi } }
  results: { pvc: perf-results }
  startDelaySeconds: 30
  timeoutMinutes: 10
EOF

echo "== following $RUN"
last=""
for _ in $(seq 1 180); do
  phase=$($K -n $NS get lt "$RUN" -o jsonpath='{.status.phase}' 2>/dev/null)
  msg=$($K -n $NS get lt "$RUN" -o jsonpath='{.status.message}' 2>/dev/null)
  [ "$phase|$msg" != "$last" ] && echo "  $(date +%H:%M:%S) ${phase:-<none>}: $msg" && last="$phase|$msg"
  case "$phase" in Completed|Failed) break;; esac
  sleep 5
done

echo "== result"
$K -n $NS get lt "$RUN" -o jsonpath='{"phase: "}{.status.phase}{"\nresult: "}{.status.result}{"\nworkers succeeded: "}{.status.workersSucceeded}{"\nstartAt: "}{.status.startAt}{"\nreport: "}{.status.reportPath}{"\nmessage: "}{.status.message}{"\n"}'
$K -n $NS get jobs -l perf.codelabuk.org/loadtest="$RUN"

run_dir=$($K -n $NS get lt "$RUN" -o jsonpath='{.status.runDir}')
if [ -n "$run_dir" ]; then
  echo "== copying the run folder back to $OUT/$RUN"
  $K -n $NS delete pod results-reader --ignore-not-found >/dev/null
  $K -n $NS run results-reader --image=labuk-perf-runner:$VERSION --restart=Never --command \
    --overrides='{"spec":{"securityContext":{"runAsUser":1000,"fsGroup":1000},"containers":[{"name":"results-reader","image":"labuk-perf-runner:'"$VERSION"'","command":["sleep","600"],"volumeMounts":[{"name":"r","mountPath":"/results"}]}],"volumes":[{"name":"r","persistentVolumeClaim":{"claimName":"perf-results"}}]}}' \
    -- sleep 600 >/dev/null
  $K -n $NS wait --for=condition=Ready pod/results-reader --timeout=2m >/dev/null
  $K -n $NS exec results-reader -- sh -c "cd $run_dir && find . -maxdepth 3 | sort | head -30"
  $K -n $NS cp "results-reader:$run_dir" "$OUT/$RUN" >/dev/null 2>&1 && echo "  copied - open $OUT/$RUN/report/merged/index.html"
  $K -n $NS delete pod results-reader --wait=false >/dev/null
fi
[ "$($K -n $NS get lt "$RUN" -o jsonpath='{.status.phase}')" = "Completed" ]
