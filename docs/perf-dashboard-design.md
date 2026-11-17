# perf-dashboard — design

A web UI for distributed load tests on Kubernetes: what Spark's driver UI + history server are for Spark jobs.
It lives in this repo (`perf-dashboard/`), is deployed with its own Helm chart, and uses the same stack as the
team's spark-dashboard (Flask + Kubernetes/MinIO clients, React + react-query + Vite) so one team can run both.

## What it shows

| Page | Content | Data source |
|---|---|---|
| **Runs** (home) | Every LoadTest: name, plan, phase, result, workers done/total, start, duration; auto-refresh | Kubernetes API (`LoadTest` resources) |
| **Run detail** | Phase timeline; summary cards (requests, errors %, req/s, p95); live chart while running; per-request table; **workers table** (pod, node, state, restarts, live req/s and users per worker, log viewer); the run's Jobs; **Report** button; the LoadTest spec | Kubernetes API, worker `/metrics`, MinIO |
| **Report** | The merged Gatling HTML report, served straight from MinIO in the browser | MinIO (`perf-results/runs/<run>/report/merged/…`) |
| **New run** | Form: plan (Git repo + ref + file, or ConfigMap + file), workers, image, env, secrets, CPU/memory, start delay → creates a LoadTest | Kubernetes API |
| **History** | All run folders in MinIO, including runs whose LoadTest was deleted, with their final numbers | MinIO (`summary.json`) |

## Architecture

```
Browser ──► perf-dashboard pod (gunicorn + Flask, serves the built React app)
              ├─ Kubernetes API : LoadTests (list/get/create/delete), their Jobs and pods, pod logs
              ├─ worker pods    : GET http://<pod-ip>:<metrics port>/metrics  (only while the run is Running)
              └─ MinIO / S3     : run folders, summary.json, worker-N.json, the merged HTML report
```

### Live vs. final numbers
- **While running**: the backend scrapes every running worker's Prometheus endpoint and returns per-worker and
  summed counters; the browser turns successive samples into rates (req/s, errors/s) and draws the chart.
  Requires `spec.metrics.enabled` (on by default for runs created from the dashboard).
- **After the run**: worker pods are gone (live endpoints with them), so final numbers come from small JSON
  summaries written next to the logs — Gatling's binary `simulation.log` is not readable from Python:
  - `perf worker` writes `logs/worker-<i>.json` (that worker's totals and per-request stats);
  - `perf report` writes `report/summary.json` (the merged run's totals and per-request stats).
  Exact percentiles are computed from the log in Scala (`RunSummary`), not from histogram buckets.

### API (backend)

| Method + path | Purpose |
|---|---|
| `GET /api/health` | liveness |
| `GET /api/config` | namespaces watched, bucket, defaults for the New-run form |
| `GET /api/loadtests` | all LoadTests in the watched namespaces |
| `GET /api/loadtests/<ns>/<name>` | spec + status + Jobs + pods (worker index, node, state, restarts, times) |
| `GET /api/loadtests/<ns>/<name>/metrics` | live: per-worker and summed counters scraped from the workers |
| `GET /api/loadtests/<ns>/<name>/summary` | final: `summary.json` + `worker-*.json` from MinIO |
| `POST /api/loadtests` | create a run (validated server-side) |
| `DELETE /api/loadtests/<ns>/<name>` | delete / cancel (Jobs and pods go with it) |
| `GET /api/pods/<ns>/<pod>/logs?tail=N` | pod log |
| `GET /api/runs` | run folders in MinIO with their summary |
| `GET /reports/<run>/<file>` | streams report files from MinIO (only keys under the runs prefix; no `..`) |

## Deployment
- Image: multi-stage Dockerfile (Node build → Python slim + gunicorn).
- Helm chart `charts/perf-dashboard`: Deployment, Service, optional Ingress, ServiceAccount + ClusterRole
  (`loadtests` get/list/watch/create/delete, `jobs` get/list, `pods` + `pods/log` get/list), S3 settings and
  credentials Secret, watched namespaces, default runner image.
- Network: the dashboard pod must reach worker pod IPs on the metrics port (default 9464).

## Scope limits (v1)
- **Results in S3/MinIO only.** Runs with `results.pvc` show status, pods and logs, but no report/summary
  (the dashboard does not mount the volume).
- **No login.** Anyone who reaches the URL can start and delete runs - keep it behind the ingress /
  network rules like the spark-dashboard; SSO (OIDC) is a later step.
- Live metrics are polled (every 5 s), not streamed; history is a list of run folders, no run-to-run comparison yet.

## Testing
- Backend: pytest with the Kubernetes and MinIO clients faked (list/detail/create/delete, metrics parsing and
  summing, report path guard).
- `RunSummary`: Scala unit test against the real Gatling log fixture.
- End to end: on Docker Desktop Kubernetes — run a LoadTest, watch it live in the dashboard API, open the report
  through `/reports/…`.
