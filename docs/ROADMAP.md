# Roadmap

Order of work and what "done" means for each module. Sizes are relative (S/M/L), not estimates.

```
gatling-api-core ──► gatling-sql ──┐
       │                           ├──► gatling-runner ──► loadtest-operator ──► platform (Grafana, storage)
       └── hardening (v1.1) ───────┘
```

| # | Module | Status | Size |
|---|---|---|---|
| 1 | `gatling-api-core` v1.1 — hardening | H1–H5 done; extra step e2e tests open | S–M |
| 2 | `gatling-sql` | v1 done (verified on Derby; Oracle not yet tried on a real DB) | M |
| 3 | `gatling-runner` | v1 done (Docker image configured, not yet built) | M |
| 4 | `loadtest-operator` | v1 done — tested against fabric8's mock API server; real-cluster run pending | L |
| 5 | Platform — live metrics, result storage | planned | S–M |

---

## 1. `gatling-api-core`

**v1 — done:** YAML engine, API catalog, checks/extraction, flow steps, CSV data, setup + shared lists,
auth (login/jwt/static, auto-refresh), open/closed load, throttle, SLAs, validation with exact error locations,
plugin SPI, worker split.
Added after v1: stepped load (`incrementUsersPerSec` / `incrementConcurrentUsers`), rates in `/s`, `/min`, `/h`,
typo checks inside load steps, load generator heap setting (`-Dperf.heap`, default 2g — the Gatling plugin
default is 1 GB). Measured: 5,000 concurrent users on a 4-CPU / 16 GB laptop, 0 errors.

**v1.1 — hardening for distributed runs** — H1–H5 implemented and verified with
`scripts/local-distributed-demo.sh` (setup once → 2 workers, 15/15 split, no overlap → merged report;
the transaction SLA failed on one worker alone and passed on the merged data, as intended).

| Item | Change |
|---|---|
| H1. SLAs judged on the merged result | **Finding:** Gatling 3.13 writes a binary `simulation.log` and its reports-only mode reads exactly one file — it can no longer merge several logs. So the core gets a **log merger** (records re-based to a common start, string cache rebuilt, SLAs kept from the header) and a `Merge` command that merges pod logs, then runs Gatling reports-only on the result → one HTML report + SLA verdict (exit code 2 = SLA failed). Pods keep SLAs in their logs; the launcher treats a pod's SLA result as informational. The merger is tied to the pinned Gatling version's log format and guarded by an end-to-end test. |
| H2. Same setup data on every pod | Shared lists are sorted before slicing. New **setup-only mode** (`-Dmode=setup -Dshared.out=file.json`) runs setup once and writes the full lists; workers started with `-Dshared.in=file.json` skip setup and take their slice from the file. |
| H3. Synchronised start | `-Dstart.at=<epoch millis or ISO-8601>` — each pod waits until then before injecting load. |
| H4. Failed polls visible in the report | A `pollUntil` that gives up records a named KO entry with its `failMessage` (and, with `name:`, an OK entry timing how long the poll took). |
| H5. Validate-only mode | `Validate` command: load + check a plan, read its data, print the per-worker split for `--workers N`; exit 0/1. Reused by runner, CI and operator. |
| Tests | ✅ local two-worker run merged into one report; ✅ unit tests for start time, shared lists, validate. **Open:** end-to-end coverage for `randomSwitch`, `retry`, `forEach`, `repeat`, `if`, `throttle`, closed model, form/raw/file bodies, `static`/`jwt` auth variants. |

**Backlog (v1.2, driven by real plans):** mTLS client certificates, proxy, per-request timeouts, multipart
upload, XML/SOAP (XPath) checks, JSON data files, JSON Schema for IDE autocomplete.

---

## 2. `gatling-sql` — database load testing

Replace JMeter's JDBC Request and LoadRunner's JDBC/ODBC protocol for **any JDBC database**, Oracle first.

```yaml
dataSources:
  ordersDb:
    type: jdbc
    url: ${DB_URL}
    user: ${DB_USER}
    password: ${DB_PASSWORD}
    pool: { size: 50, connectionTimeout: 5s }
    allowWrites: false             # INSERT/UPDATE/DELETE/CALL refused unless true
  customers:                       # DB-backed feeder
    type: jdbc
    datasource: ordersDb
    query: "SELECT customer_id FROM customers WHERE status = 'ACTIVE'"
    strategy: queue

steps:
  - sql:
      datasource: ordersDb
      name: find-orders
      query: "SELECT id, status FROM orders WHERE customer_id = ?"
      params: [ "#{customerId}" ]
      checks: [ { rowCount: ">= 1" } ]
      extract: { orderId: { column: id } }
  - sql: { datasource: ordersDb, update: "UPDATE orders SET status = ? WHERE id = ?", params: [ PAID, "#{orderId}" ] }
  - sql:
      datasource: ordersDb
      call: "{call pkg_orders.place_order(?, ?, ?)}"
      params: [ "#{customerId}", "#{amount}", { out: number, saveAs: newOrderId } ]
```

Work items:
1. HikariCP pool per data source, opened at test start, closed at the end.
2. `sql` step: `query`, `update`, `call` (OUT params, REF CURSOR), `batch`; parameter types; fetch size; commit/rollback.
3. Dedicated thread pool sized to the connection pool — JDBC blocks and must never run on Gatling's threads.
4. Each statement reported as a request (timings, OK/KO, SLAs); optional separate "connection wait" timing.
5. Checks (`rowCount`, column equals) and extraction (one row / all rows).
6. JDBC feeder.
7. Drivers: Oracle `ojdbc11`, PostgreSQL, SQL Server, MySQL bundled in the runner; others via `drivers/`.
8. Pool budget warning: `pool size × workers` vs. an allowed maximum of DB sessions.

Tests: Derby in-memory (chosen over H2 because it supports procedures with OUT parameters); optional
Oracle Free container test.
**Done when** mixed API+SQL and pure SQL plans run under load against Derby and Oracle with SQL timings and SLAs in the report.

**Status (v1):** items 1–6 and 8 done — pools, `query` / `update` / `call` (OUT params incl. `cursor`,
procedure result rows), `rollback`, own thread pool, report entries + optional connection-wait entry,
checks, extraction, JDBC feeder, `allowWrites` guard, `maxSessions` warning, fail-fast on unreachable DB.
Verified: 6 unit tests + an end-to-end run (660 requests, 0 errors) mixing DB-only load and an API → DB flow.
**Open:** `batch` statements; a run against a real **Oracle** (REF CURSOR / PL/SQL OUT behaviour, driver
properties) — needs a database; SQL Server / MySQL drivers (add when needed, no code change).
Core changes it needed: plugin hooks `isFeeder` and `reportNames`; SLF4J pinned to 2.x in the parent pom
(HikariCP's 1.7.x silently disabled all logging).

---

## 3. `gatling-runner` — what testers and pods run

No Maven or IDE for testers; the same commands locally and in Kubernetes.

```
perf validate plans/x.yaml [--workers 10]
perf run      plans/x.yaml [--scenario a,b] [-DNAME=value]
perf report   <folder of simulation.log files>     # merge → one report + SLA verdict
perf worker                                        # pod entrypoint, settings from env
perf submit   plans/x.yaml --workers 10             # creates a LoadTest (after the operator)
```

Work items:
1. Launcher scripts `perf.sh` / `perf.cmd` around `YamlSimulation`, `Validate` and `Merge`.
2. Distribution zip: core + sql + Gatling + drivers + `logback.xml` + `gatling.conf`.
3. Docker image built with Jib (no Docker daemon needed): Temurin 17 JRE, non-root, JVM defaults.
4. Worker mode: plan location, worker index/count, start time, results target from env; `--no-reports`;
   upload `simulation.log`; treat exit code 2 (SLA) as informational on a pod.
5. Report mode: download logs → merge → report → SLA verdict → upload; exit 0/2.
6. ~~Live metrics switch: Graphite data writer enabled by env.~~ **Dropped: Gatling 3.13's open-source edition
   has no Graphite writer** (only console + file; verified in its source and with a listener). Live metrics
   moved to the platform section as a design item.
7. Tuning defaults for large user counts: heap, file descriptors, ephemeral ports.
8. CI examples (Jenkins, GitLab): validate on merge request, run on schedule, fail on SLA.

**Done when** a tester with only Java (or Docker) can validate, run and merge, and the same image runs as a pod.

**Status (v1):** items 1, 2, 4, 5 done and verified from the unzipped distribution against the mock API —
`perf validate`, `perf run`, `perf setup` → 2 × `perf worker` (env-driven, synchronised start, 15/15 split) →
`perf report` (merged report, SLA verdict), bad plan = one-line error + exit 1; `perf.cmd` verified on Windows.
Item 3 (Jib image) configured; build not yet run. Item 6 dropped (see above). **Open:** build + smoke-test the
image, item 7 (OS tuning guidance), item 8 (CI examples).

---

## 4. `loadtest-operator` — distributed runs on Kubernetes

Java 17, Java Operator SDK on the fabric8 client, CRD generated from Java classes, Helm chart.

```yaml
apiVersion: perf.codelabuk.org/v1
kind: LoadTest
metadata: { name: orders-peak-2026-11-20 }
spec:
  image: registry/labuk-perf-runner:1.2.0
  plan:
    git: { repo: https://git/perf-tests.git, ref: main, path: plans/orders-peak.yaml, secretRef: git-creds }
    # alternatives: configMap (small tests only - 1 MB limit) | pvc | baked into the image
  workers: 10
  scenarios: [ checkout ]
  env: { BASE_URL: https://sit.api }
  secretRefs: [ perf-db-creds ]
  resources: { cpu: "2", memory: 4Gi }
  scheduling: { nodeSelector: { pool: perf-spot } }
  results: { s3: { endpoint: http://minio:9000, bucket: perf-results } }
  startDelay: 30s
  ttlAfterFinished: 1h
```

| Phase | Operator action |
|---|---|
| `Validating` | Job runs `perf validate --workers N`; a bad plan fails before any load |
| `Setup` | If the plan has `setup`: one Job runs setup once and publishes the shared lists (H2) |
| `Starting` | Indexed Job with N workers; when all pods are ready, sets `startAt` (H3) |
| `Running` | Watches pods; crash policy (fail fast / continue); timeout cancels |
| `Aggregating` | Job runs `perf report`: merged report + SLA verdict (H1) |
| `Completed` / `Failed` | `status`: report URL, SLA result, duration, per-worker summary; cleanup after TTL |

Work items: CRD + status, reconciler, Job/Pod templates, RBAC, leader election, cancel by delete,
events + `kubectl get loadtests` columns, `perf submit` / `perf status`, Helm chart.
Tests: JOSDK test utilities; end-to-end on kind/k3d against the mock API.
**Done when** `perf submit --workers 5` runs the sample flow on 5 pods with one start time, no item processed
twice, and one merged report with the right SLA verdict.

**Status (v1):** Java 17, JOSDK 5.1.1 / fabric8 7.3.1. `LoadTest` CRD (`perf.codelabuk.org/v1alpha1`, short
name `lt`) generated from Java; lifecycle Validating → Setup → Starting → Running → Aggregating →
Completed/Failed with facts passed back through termination messages (`perf validate/report --facts-file`);
plan from Git / ConfigMap / image; results on an existing PVC; owner references, TTL, no retries, non-root;
manifests in `deploy/`. Verified: 9 tests (whole lifecycle against fabric8's mock API server, failure paths,
idempotency, pod details, manifests parse) + the runner's env-driven worker path (`JOB_COMPLETION_INDEX`).
**Not done / open:**
- **Never run on a real cluster yet** — needs the two images built and a cluster (minikube is installed here).
- Start time is set when the workers Job is created (now + `startDelaySeconds`), not when all pods are ready —
  a pod that pulls its image slower than the delay starts late (it logs a warning).
- `perf submit` / `perf status` CLI, Helm chart, printer columns for `kubectl get`, leader election (single replica now).
- Results only on a PVC (no S3/MinIO upload yet); multi-node clusters need a ReadWriteMany storage class.

---

## 5. Platform (free, self-hosted)

| Part | Choice |
|---|---|
| Result storage | MinIO (S3-compatible) or the cloud's bucket, with expiry rules |
| Live metrics | **Needs design** — Gatling 3.13 OSS cannot stream metrics (no Graphite writer). Options: (a) our own exporter: a recording check on every request + the SQL action feed a Prometheus endpoint per worker → Grafana; (b) watch the console summary each worker prints every 5 s (pod logs); (c) rely on server-side APM/Prometheus during tests and Gatling's report after. |
| Server metrics | Existing Prometheus/APM alongside the Gatling dashboard |
| Load generators | Dedicated (spot) node pool that scales to zero between tests |

---

## Open decisions

1. Kubernetes platform (EKS / AKS / GKE / OpenShift / on-prem).
2. Result storage: MinIO or cloud bucket.
3. How test files reach pods: Git pull (recommended), baked image, or shared volume.
4. Oracle versions and features (stored procedures, REF CURSOR?).
5. Existing metrics stack (Prometheus/Grafana, InfluxDB?).
