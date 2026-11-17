#!/usr/bin/env bash
# Distributed end-to-end run on Docker Desktop's Kubernetes, results in the cluster's MinIO:
#   images (docker build - same Docker engine as the cluster, nothing to load)
#   -> operator via Helm (2 replicas, leader election) in namespace perf
#   -> mock API (system under test) in namespace perf
#   -> LoadTest: SR flow on 3 workers, results.s3 -> s3://perf-results/runs/...
#   -> perf status --watch -> perf fetch the merged report to loadtest-operator/target/e2e/
# Only touches namespace perf and the bucket perf-results. Other namespaces are left alone.
#
# Usage (repo root, after `mvn install`):  bash scripts/docker-desktop-e2e.sh
#   SKIP_IMAGES=1  reuse built images      WORKERS=3   number of workers
set -u
cd "$(dirname "$0")/.."
CTX=docker-desktop
K="kubectl --context $CTX"
NS=perf
VERSION=1.0-SNAPSHOT
WORKERS=${WORKERS:-3}
RUN="sr-flow-$(date +%H%M%S)"
OUT="loadtest-operator/target/e2e"
PERF="gatling-runner/target/perf-$VERSION/perf-$VERSION/bin/perf"
mkdir -p "$OUT"

if [ -z "${SKIP_IMAGES:-}" ]; then
  echo "== images"
  mvn -B -q -pl gatling-api-core test-compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib || exit 1
  docker build -q -t labuk-perf-runner:$VERSION gatling-runner >/dev/null || exit 1
  docker build -q -t labuk-loadtest-operator:$VERSION loadtest-operator >/dev/null || exit 1
  docker build -q -t labuk-mock-api:$VERSION -f gatling-api-core/src/test/docker/Dockerfile gatling-api-core/target >/dev/null || exit 1
fi

echo "== operator (Helm, 2 replicas)"
$K apply -f charts/loadtest-operator/crds/ >/dev/null
helm --kube-context $CTX upgrade --install loadtest-operator charts/loadtest-operator -n $NS --create-namespace \
  --set replicaCount=2 --wait --timeout 3m >/dev/null || exit 1
$K -n $NS get pods -l app.kubernetes.io/name=loadtest-operator --no-headers

echo "== mock API + MinIO credentials"
sed "s/namespace: perf-e2e/namespace: $NS/; /^kind: Namespace/,/^---/d" loadtest-operator/e2e/cluster.yaml \
  | awk 'BEGIN{RS="---\n"; ORS="---\n"} !/kind: PersistentVolumeClaim/' | $K apply -f - >/dev/null
$K -n $NS rollout status deploy/mock-api --timeout=3m >/dev/null || exit 1
MINIO_USER=$($K -n minio get secret minio-secret -o jsonpath='{.data.root-user}' | base64 -d)
MINIO_PASS=$($K -n minio get secret minio-secret -o jsonpath='{.data.root-password}' | base64 -d)
$K -n $NS create secret generic minio-creds --from-literal=AWS_ACCESS_KEY_ID="$MINIO_USER" \
  --from-literal=AWS_SECRET_ACCESS_KEY="$MINIO_PASS" --dry-run=client -o yaml | $K apply -f - >/dev/null

echo "== tests as a ConfigMap"
T="$OUT/tests"; rm -rf "$T"; mkdir -p "$T"
P=gatling-api-core/src/test/resources/plans
sed -e 's#include: \[ apis/sr.yaml \]#include: [ sr.yaml ]#' -e 's#file: data/users.csv#file: users.csv#' "$P/sr-email-status.yaml" > "$T/sr-email-status.yaml"
cp "$P/apis/sr.yaml" "$P/data/users.csv" "$T/"
$K -n $NS create configmap sr-tests --from-file="$T" --dry-run=client -o yaml | $K apply -f - >/dev/null

echo "== LoadTest $RUN ($WORKERS workers)"
cat <<EOF | $K apply -f -
apiVersion: perf.codelabuk.org/v1alpha1
kind: LoadTest
metadata: { name: $RUN, namespace: $NS }
spec:
  image: labuk-perf-runner:$VERSION
  plan: { configMap: sr-tests, file: sr-email-status.yaml }
  workers: $WORKERS
  env: { BASE_URL: "http://mock-api.$NS.svc:18080" }
  resources: { requests: { cpu: 250m, memory: 768Mi }, limits: { memory: 1Gi } }
  results:
    s3: { url: "s3://perf-results/runs", endpoint: "http://minio.minio.svc:9000", credentialsSecret: minio-creds }
  metrics: { enabled: true }
  startDelaySeconds: 30
  timeoutMinutes: 10
EOF

echo "== following $RUN"
$PERF status "$RUN" -n $NS --context $CTX --watch
verdict=$?

echo "== workers' log files and the report in MinIO"
run_dir=$($K -n $NS get lt "$RUN" -o jsonpath='{.status.runDir}')
echo "  $run_dir"
if [ -n "$run_dir" ]; then
  # the MinIO NodePort (32090) is reachable from the host on Docker Desktop
  AWS_ACCESS_KEY_ID="$MINIO_USER" AWS_SECRET_ACCESS_KEY="$MINIO_PASS" S3_ENDPOINT="http://localhost:32090" \
    $PERF fetch "$run_dir" "$OUT/$RUN"
fi
exit $verdict
