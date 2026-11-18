# labuk-gatling — Architecture

A YAML-driven performance testing platform on **open-source Gatling**, built to replace JMeter and
LoadRunner for API and database load tests, and to run them **distributed on an on-prem Kubernetes cluster**
with live tracking and a single, exact result per run.

This document explains how the pieces fit together. For *using* the tools see the [README](../README.md);
for plans and status see the [roadmap](ROADMAP.md); for the dashboard's design see
[perf-dashboard-design.md](perf-dashboard-design.md).

Diagrams are kept as source files in [`docs/architecture/`](architecture/) in two formats:
Mermaid (`.mmd`, rendered by GitHub below) and PlantUML (`.puml`).

---

## Goals and constraints

| Goal | How the architecture answers it |
|---|---|
| **Testers write no code** | Tests are YAML (plan + API catalog + CSV); one generic Gatling simulation runs any plan |
| **Replace JMeter and LoadRunner** | HTTP and SQL/PL-SQL steps, transactions, correlation, think time, pacing, rendezvous, SLAs; a converter for LoadRunner VuGen scripts |
| **No licence cost** | Gatling's open-source edition only (Apache 2.0); everything Gatling Enterprise would add (distribution, merged results, live metrics, a UI) is built here |
| **Distributed on on-prem Kubernetes** | An operator runs each test as Kubernetes Jobs; workers split the load and the data; results are merged into one report |
| **Trustworthy numbers** | One merged log per run, SLAs judged on all data, dashboard percentiles computed exactly like Gatling's report |
| **Runs anywhere** | The same `perf` command on a laptop, a VM, in CI and inside pods |

Fixed technology choices: Java 17, Scala 2.13.15, **Gatling 3.13.1** (pinned - newer 3.13.x moved to Scala
2.13.16), Java Operator SDK 5.1.1 on fabric8 7.3.1, Flask + React for the dashboard.

---

## Components at a glance

| Component | Technology | Responsibility | Location |
|---|---|---|---|
| **gatling-api-core** | Scala | YAML loading and validation, the engine (steps, auth, data, load, SLAs), worker split, log merging, live metrics, run summaries | `gatling-api-core/` |
| **gatling-sql** | Scala, HikariCP | `sql` steps (query, update, call, batch) and `jdbc` data sources, as plugins | `gatling-sql/` |
| **gatling-runner** | Scala | The `perf` command: validate, run, setup, worker, report, fetch, submit, status, convert-lr; S3 client; distribution zip and image | `gatling-runner/` |
| **loadtest-operator** | Java, JOSDK | Watches `LoadTest` resources and runs each as validate → setup → workers → report Jobs | `loadtest-operator/` |
| **perf-dashboard** | Python Flask, React | Runs, live metrics per worker, final numbers, logs, reports from S3, new runs | `perf-dashboard/` |
| **Helm charts** | Helm 3 | Install the operator (+ CRD) and the dashboard | `charts/` |
| **MinIO / S3** | (existing) | Results: shared lists, worker logs and summaries, merged reports | cluster service |

---

## 1. System context

```mermaid
%% 1. System context: who uses labuk-gatling and what runs where
flowchart LR
    tester(["Perf tester / QA"])
    ci(["CI pipeline<br/>Jenkins / GitLab"])
    dev(["Framework developer"])
    git[("Git repo<br/>YAML plans, API catalog, CSV")]

    subgraph local["Tester's machine"]
        cli["perf CLI<br/>validate | run | submit | status | fetch | convert-lr"]
    end

    subgraph k8s["Kubernetes cluster (on-prem)"]
        direction TB
        ingress["Ingress (nginx, TLS)<br/>perf-dashboard.codelabuk.dev"]
        subgraph nsperf["namespace: perf"]
            dashboard["perf-dashboard<br/>Flask API + React UI"]
            operator["loadtest-operator<br/>2 replicas, leader election"]
            crd[["LoadTest<br/>perf.codelabuk.org/v1alpha1"]]
            subgraph jobs["Jobs of one run"]
                validate["validate"]
                setup["setup (once)"]
                workers["workers x N<br/>Indexed Job, /metrics :9464"]
                report["report (merge)"]
            end
        end
        subgraph nsminio["namespace: minio"]
            minio[("MinIO / S3<br/>bucket perf-results")]
        end
        prom["Prometheus + Grafana<br/>(optional)"]
    end

    subgraph sut["Systems under test"]
        apis["HTTP / REST APIs"]
        dbs[("Databases<br/>Oracle, PostgreSQL, ... via JDBC")]
    end

    tester -->|"browser, HTTPS"| ingress --> dashboard
    tester --> cli
    ci --> cli
    dev -->|"mvn install, docker build, helm"| k8s
    cli -->|"kubectl: LoadTest, ConfigMap"| crd
    cli -->|"perf run (local load)"| apis
    dashboard -->|"create / list / delete"| crd
    operator -->|"watches"| crd
    operator -->|"creates Jobs, reads results"| jobs
    validate & setup & workers & report -.->|"clone tests"| git
    workers -->|"load"| apis
    workers -->|"load"| dbs
    setup & workers & report -->|"shared lists, logs, report, summaries"| minio
    dashboard -->|"live metrics"| workers
    dashboard -->|"summaries, reports"| minio
    prom -.->|"scrape"| workers
    cli -->|"perf fetch"| minio
```

<sub>Source: [`architecture/01-system-context.mmd`](architecture/01-system-context.mmd) · PlantUML: [`architecture/01-system-context.puml`](architecture/01-system-context.puml)</sub>

Testers start runs from the **dashboard** (browser) or the **`perf` CLI**; CI uses the CLI. Either way a
`LoadTest` resource is created in Kubernetes. The **operator** turns it into Jobs whose pods run the **perf
runner image**: they clone the tests from Git (or mount a ConfigMap), load the **systems under test**, and
write everything to **MinIO**. The dashboard reads the cluster (status, pods, logs), the workers (live
metrics) and MinIO (final numbers, reports). Prometheus/Grafana can scrape the same worker metrics.

---

## 2. Modules

```mermaid
%% 2. Modules and their dependencies
flowchart TB
    subgraph repo["labuk-gatling repository"]
        direction TB
        subgraph core["gatling-api-core (Scala)"]
            core_load["load<br/>PlanLoader, Node, RunOptions, LoadMath"]
            core_model["model<br/>TestPlan, Step, ApiDef, LoadDef, SlaDef"]
            core_build["build<br/>SimulationAssembler, ChainFactory,<br/>AuthStrategy, RecordResult"]
            core_sim["sim<br/>YamlSimulation"]
            core_data["data<br/>CSV, RecordSource, Worker split, SharedFile"]
            core_expr["expr<br/>Condition, Durations"]
            core_results["results<br/>LogMerger"]
            core_metrics["metrics<br/>LiveLogReader, LiveMetrics, RunSummary"]
            core_cli["cli<br/>Validate, Merge"]
            core_plugin["plugin SPI<br/>StepPlugin, DataSourcePlugin"]
        end
        subgraph sql["gatling-sql (Scala)"]
            sql_plugins["SqlStepPlugin, JdbcDataSourcePlugin"]
            sql_exec["SqlAction, SqlExecutor, Pools (HikariCP)"]
        end
        subgraph runner["gatling-runner (Scala)"]
            perf["Perf CLI"]
            kube["Kube: submit / status"]
            s3["S3 client (SigV4)"]
            lr["lr.VuGenConverter"]
            dist["perf-x.zip + Dockerfile"]
        end
        subgraph operator["loadtest-operator (Java)"]
            op_crd["LoadTest CRD (generated)"]
            op_flow["LoadTestReconciler, LoadTestFlow"]
            op_jobs["JobFactory"]
        end
        subgraph dashboard["perf-dashboard (Python + TypeScript)"]
            dash_api["Flask: api, reports, kube, live, storage"]
            dash_ui["React: Runs, Run detail, New run, History"]
        end
        subgraph charts["Helm charts"]
            chart_op["charts/loadtest-operator"]
            chart_dash["charts/perf-dashboard"]
        end
    end

    gatling["Gatling 3.13.1 (open source)<br/>Scala 2.13.15"]
    josdk["Java Operator SDK 5.1.1<br/>fabric8 7.3.1"]

    core_load --> core_model
    core_build --> core_model & core_expr & core_data & core_plugin & core_metrics
    core_sim --> core_load & core_build & core_metrics
    core_cli --> core_load & core_results
    core --> gatling
    sql_plugins -.->|"implements"| core_plugin
    sql --> core
    runner --> sql
    perf --> core_cli & core_sim & core_metrics & s3 & kube & lr
    operator --> josdk
    op_jobs -.->|"pods run the runner image"| dist
    op_crd -.->|"copied into"| chart_op
    dash_api -.->|"reads"| op_crd
    chart_dash -.-> dashboard
```

<sub>Source: [`architecture/02-modules.mmd`](architecture/02-modules.mmd) · PlantUML: [`architecture/02-modules.puml`](architecture/02-modules.puml)</sub>

| Module / package | Contents |
|---|---|
| `core/load` | `PlanLoader` (YAML → model, `${NAME}` placeholders, includes, typo and cross-reference checks), `RunOptions`, `LoadMath` |
| `core/model` | The plan model: `TestPlan`, `ApiDef`, `Step` (all step types), `LoadDef`, `SlaDef`, `AuthDef` — no Gatling types |
| `core/build` | `SimulationAssembler` (per-worker populations, setup and shares, injection, throttle, SLAs), `ChainFactory` (steps → Gatling chains), `AuthStrategy`, `RecordResult` |
| `core/sim` | `YamlSimulation` — the one generic Gatling simulation |
| `core/data`, `core/expr` | CSV, record strategies, worker slicing, shared lists; conditions and durations |
| `core/results` | `LogMerger` — combines workers' binary logs |
| `core/metrics` | `LiveLogReader` (follows a growing log), `LiveMetrics` (Prometheus endpoint), `RunSummary` (exact JSON summaries) |
| `core/cli` | `Validate`, `Merge` |
| `core/plugin` | Extension points `StepPlugin`, `DataSourcePlugin` (Java `ServiceLoader`) |
| `gatling-sql` | `SqlStepPlugin`, `JdbcDataSourcePlugin`, `SqlAction` (own thread pool), `SqlExecutor`, `Pools` |
| `gatling-runner` | `Perf` (CLI), `Kube` (submit/status via kubectl), `S3` (SigV4 client), `lr.VuGenConverter`, `Facts` |
| `loadtest-operator` | `LoadTest` CRD classes, `LoadTestReconciler`, `LoadTestFlow` (state machine), `JobFactory` |
| `perf-dashboard` | `routes/api.py`, `routes/reports.py`, `kube.py`, `live.py`, `storage.py`; React pages |

Dependencies point one way: `gatling-runner → gatling-sql → gatling-api-core → Gatling`. The operator does not
link the engine — it starts pods from the runner image. The dashboard talks to the Kubernetes API, the worker
pods and S3, never to the engine directly.

---

## 3. How a test is defined

A test is data, not code:

```
perf-tests/                      (any Git repository, or a ConfigMap for small flat sets)
├── apis/orders.yaml             API catalog: each request defined once (method, path, headers, body, checks, extract)
├── data/users.csv               test data, one row per virtual user (queue / circular / random / shuffle)
├── bodies/create-order.json     request bodies with #{variables}
└── plans/orders-peak.yaml       scenarios (steps), load profile, auth, setup, SLAs
```

- **Steps**: `api`, `sql`, `transaction`, `pollUntil`, `forEach`, `repeat`, `loop`, `if`, `randomSwitch`,
  `retry`, `wait`, `pace`, `rendezvous`, `set`, `feed`, `log`, `stopUserIfFailed`.
- **Variables**: `#{name}` at run time (CSV columns, extracted values); `${NAME}` at load time (environment —
  e.g. `BASE_URL` per environment, secrets from Kubernetes Secrets).
- **Load**: open (arrival rate), closed (concurrency) and stepped models; rates in `/s`, `/min`, `/h`; throttle.
- **SLAs**: `global.*`, `api.<name>.*`, `transaction.<name>.*` response-time percentiles, error %, throughput.
- **LoadRunner**: `perf convert-lr` turns a VuGen web script (`vuser_init.c`, `Action*.c`, `vuser_end.c`,
  `.prm`/`.dat`) into the same files, with a report of anything not converted.

---

## 4. Engine

```mermaid
%% 5. Engine internals: from YAML to Gatling
flowchart LR
    subgraph inputs["Test files (no code)"]
        plan["plan.yaml<br/>scenarios, load, SLAs"]
        catalog["apis/*.yaml<br/>API catalog"]
        data["data/*.csv"]
        env["-DNAME / env vars<br/>fill ${NAME}"]
        vugen["LoadRunner VuGen script"]
    end

    conv["perf convert-lr<br/>VuGenConverter"]
    vugen --> conv --> plan & catalog & data

    loader["PlanLoader<br/>placeholders, includes,<br/>typo + cross checks"]
    model["TestPlan model<br/>steps, auth, load, SLAs"]
    plan & catalog & data & env --> loader --> model

    subgraph assemble["SimulationAssembler (per worker)"]
        split["Worker split<br/>users, rates, CSV rows, shared lists"]
        setupscn["setup scenario<br/>(once) + shares"]
        chain["ChainFactory<br/>api, transaction, pollUntil, forEach,<br/>if, retry, wait, pace, rendezvous ..."]
        auth["AuthStrategy<br/>login / jwt / static, refresh"]
        inj["injection + throttle"]
        sla["SLAs -> Gatling assertions"]
    end
    plugins["Plugins (ServiceLoader)<br/>sql step, jdbc data source"]

    model --> split --> setupscn & chain & inj
    chain --> auth
    chain -.-> plugins
    model --> sla

    sim["YamlSimulation"]
    setupscn & chain & inj & sla --> sim

    subgraph runtime["Gatling runtime"]
        netty["HTTP over Netty<br/>(non-blocking)"]
        jdbc["SQL thread pool<br/>HikariCP per connection"]
    end
    sim --> netty & jdbc
    netty --> apis["APIs"]
    jdbc --> dbs[("Databases")]
```

<sub>Source: [`architecture/05-engine.mmd`](architecture/05-engine.mmd) · PlantUML: [`architecture/05-engine.puml`](architecture/05-engine.puml)</sub>

1. **Load and validate** — `PlanLoader` resolves `${NAME}`, follows `include`s (paths are relative to the file
   they are written in), and rejects mistakes with the exact file and YAML path: unknown keys, undefined APIs or
   feeders, invalid conditions or durations, SLA targets that do not exist, a `pollUntil` that could never end.
2. **Assemble for this worker** — `SimulationAssembler` divides users, rates and throttle targets by the worker
   count, slices CSV rows and shared lists so no two workers use the same item, builds the optional **setup**
   scenario (runs once, then hands lists to the users), the scenario chains, injection profiles and SLA
   assertions.
3. **Run** — `YamlSimulation` hands everything to Gatling. HTTP runs on Gatling's non-blocking Netty event
   loops; **SQL runs on its own thread pool** (one per connection pool) so blocking JDBC calls never distort
   HTTP timings.

**Extension points**: a new step type or data source is a class implementing `StepPlugin` /
`DataSourcePlugin`, registered under `META-INF/services` and placed on the classpath — `gatling-sql` is built
exactly this way. Plugins can declare report names so SLAs can target their steps.

**Runtime safeguards** built into the engine and launcher:
- Gatling 3.13 deadlocks at start-up when the JVM sees **one CPU**; `perf` restarts itself with
  `-XX:ActiveProcessorCount=2` (thread counts only — the CPU limit still applies).
- A throttle shorter than the load silently ends the scenario in Gatling; validate and run warn about it.
- Gatling's anonymous usage analytics are switched off.

---

## 5. Distributed runs

```mermaid
%% 3. Lifecycle of one distributed run (LoadTest)
sequenceDiagram
    autonumber
    actor T as Tester
    participant D as perf-dashboard / perf submit
    participant K as Kubernetes API
    participant O as loadtest-operator
    participant V as validate pod
    participant S as setup pod
    participant W as worker pods (x N)
    participant R as report pod
    participant M as MinIO / S3
    participant SUT as APIs / DBs

    T->>D: New run (plan, workers, env)
    D->>K: create LoadTest
    O->>K: reconcile (every 5 s)
    O->>K: create Job "validate"
    K->>V: start (init: git clone tests)
    V->>V: perf validate --facts-file /dev/termination-log
    V-->>O: facts: valid, hasSetup, scenarios
    alt plan has setup.share
        O->>K: create Job "setup"
        S->>SUT: setup steps (e.g. login, load list)
        S->>M: shared.json
    end
    O->>K: create Indexed Job "workers" (START_AT = now + delay)
    K->>W: N pods (JOB_COMPLETION_INDEX = worker number)
    W->>M: read shared.json (if any)
    W->>W: wait for START_AT
    loop during the test
        W->>SUT: load (users / rates divided by N)
        D->>W: GET /metrics (live, every 3 s)
    end
    W->>M: logs/worker-i.log + worker-i.json
    O->>K: create Job "report" (all workers succeeded)
    R->>M: download logs
    R->>R: LogMerger + Gatling reports-only (SLAs on all data)
    R->>M: report/merged/* + report/summary.json
    R-->>O: facts: reportBuilt, slaPassed, report path
    O->>K: status Completed, result Passed / SLAFailed
    D->>M: summary.json, worker-*.json, report files
    D-->>T: final numbers + Open report
```

<sub>Source: [`architecture/03-run-lifecycle.mmd`](architecture/03-run-lifecycle.mmd) · PlantUML: [`architecture/03-run-lifecycle.puml`](architecture/03-run-lifecycle.puml)</sub>

A run is one `LoadTest` resource (`perf.codelabuk.org/v1alpha1`). `LoadTestFlow` is an idempotent state
machine — every reconcile looks at what exists and moves one step, so an operator restart simply continues.

| Phase | Job | What happens | Result passed back |
|---|---|---|---|
| Validating | `<run>-validate` | `perf validate --workers N`; a bad plan stops here | facts JSON via `/dev/termination-log`: valid, hasSetup, scenarios, error message |
| Setup | `<run>-setup` | `perf setup` — only if the plan has `setup.share`; runs **once** for the whole test | `shared.json` in the run folder |
| Starting → Running | `<run>-workers` | **Indexed Job** with N pods; `JOB_COMPLETION_INDEX` = worker number; all wait for `START_AT` (creation + `startDelaySeconds`) | `worker-<i>.log` + `worker-<i>.json` |
| Aggregating | `<run>-report` | `perf report`: merge logs, build the HTML report, judge SLAs | facts JSON: reportBuilt, slaPassed, report path |
| Completed / Failed | — | `status.result` = `Passed` / `SLAFailed`, or the reason for failure | |

Design points:
- **Facts through termination messages** — validate and report containers write a small JSON to
  `/dev/termination-log`; the operator reads it from the pod status. No extra API, no shared volume needed.
- **No retries for workers** (`backoffLimit: 0`) — a retried worker would start late and skew the test; a crash
  fails the run with the reason.
- **Leader election** (a Lease in the operator's namespace) — two operator replicas are safe; one works.
- **Ownership** — every Job is owned by its LoadTest: deleting the LoadTest stops and removes the run.
- **Tests from Git** (init container fetches a branch, tag or commit), a **ConfigMap** (small, flat, 1 MB) or
  **baked into the image**. Secrets reach pods only as environment variables from Kubernetes Secrets.

The same sequence works without Kubernetes: `perf setup`, N × `perf worker` with `WORKER_INDEX` /
`WORKER_COUNT` / `START_AT`, then `perf report`.

---

## 6. Results and metrics

```mermaid
%% 4. Results and metrics: live while running, exact when finished
flowchart LR
    subgraph worker["each worker pod"]
        engine["Gatling engine"]
        log[("simulation.log<br/>binary, written in chunks")]
        reader["LiveLogReader<br/>follows the log"]
        metrics["LiveMetrics<br/>:9464/metrics"]
        wsum["RunSummary<br/>worker-i.json"]
        engine --> log --> reader --> metrics
        log -->|"at the end"| wsum
    end

    subgraph store["MinIO / S3: perf-results/runs/run-folder/"]
        shared["shared.json"]
        logs["logs/worker-i.log<br/>logs/worker-i.json"]
        html["report/merged/index.html ..."]
        summary["report/summary.json"]
    end

    subgraph reportpod["report pod"]
        merger["LogMerger<br/>re-base timestamps, one log"]
        gatlingro["Gatling reports-only<br/>HTML + SLA verdict"]
        rsum["RunSummary<br/>(t-digest, like the report)"]
        merger --> gatlingro
        merger --> rsum
    end

    log -->|"upload"| logs
    wsum -->|"upload"| logs
    logs --> merger
    gatlingro --> html
    rsum --> summary

    dash["perf-dashboard"]
    prom["Prometheus / Grafana"]
    metrics -->|"live, summed across workers"| dash
    metrics -.-> prom
    logs -->|"per-worker final"| dash
    summary -->|"run final"| dash
    html -->|"/reports/... in the browser"| dash
```

<sub>Source: [`architecture/04-results-flow.mmd`](architecture/04-results-flow.mmd) · PlantUML: [`architecture/04-results-flow.puml`](architecture/04-results-flow.puml)</sub>

**Run folder** (S3 / MinIO, or a shared volume):

```
perf-results/runs/<run>-<uid8>/
├── shared.json                     setup's lists (when the plan has setup)
├── logs/worker-<i>.log             each worker's Gatling log (binary)
├── logs/worker-<i>.json            each worker's final numbers
└── report/
    ├── summary.json                the run's final numbers + SLA verdict
    └── merged/index.html …         the merged Gatling HTML report
```

- **One result per run.** Gatling 3.13 writes a binary `simulation.log` and its reports-only mode reads exactly
  one file, so `LogMerger` rewrites all workers' records into one log (timestamps re-based to the earliest start,
  string cache rebuilt, SLAs kept from the header). Gatling then builds the report and judges the SLAs **on all
  the data** — a single worker's SLA printout is informational only.
- **Live metrics without Gatling Enterprise.** Each worker follows its own log while Gatling writes it
  (`LiveLogReader` handles records cut off mid-write) and serves Prometheus metrics on `:9464/metrics`. The
  numbers come from the same records as the report. Gatling writes the log in chunks, so live numbers trail by
  ~1 s at high load and up to ~20 s at a trickle (`perf` halves the chunk size when metrics are on).
- **Exact final numbers.** `RunSummary` computes counts and percentiles from the log with the same t-digest
  (compression 100, values in log order) as Gatling's report, so the dashboard and the HTML report agree.

---

## 7. perf-dashboard

| Concern | Design |
|---|---|
| Live view | Backend scrapes every running worker's `/metrics` in parallel and sums them; the browser turns successive samples into rates. The last numbers of finished workers are remembered so totals never decrease (one gunicorn process, threads for concurrency) |
| Final view | `summary.json` + `worker-*.json` from S3 — available after the pods are gone |
| Reports | `/reports/<key>` streams the report files from S3; only keys under the results prefix, `..` rejected |
| Starting runs | `POST /api/loadtests` builds and validates a LoadTest (Git or ConfigMap plan, workers, resources, env, secrets) |
| Access to the cluster | A ClusterRole: LoadTests get/list/watch/create/delete, Jobs get/list, Pods and logs get/list |

---

## 8. Deployment

```mermaid
%% 6. Deployment: images, charts, namespaces
flowchart TB
    subgraph build["Build (developer / CI)"]
        mvn["mvn install<br/>jars, perf zip, generated CRD"]
        img_runner["labuk-perf-runner<br/>gatling-runner/Dockerfile"]
        img_op["labuk-loadtest-operator<br/>loadtest-operator/Dockerfile"]
        img_dash["labuk-perf-dashboard<br/>perf-dashboard/Dockerfile"]
        mvn --> img_runner & img_op
    end
    registry[("Container registry<br/>e.g. Harbor / Nexus")]
    img_runner & img_op & img_dash --> registry

    subgraph cluster["Kubernetes cluster"]
        crd[["CRD loadtests.perf.codelabuk.org"]]
        subgraph perfns["namespace: perf"]
            op["Deployment loadtest-operator<br/>+ ServiceAccount, ClusterRole,<br/>Lease Role (leader election)"]
            dash["Deployment perf-dashboard<br/>+ Service, ClusterRole"]
            ing["Ingress perf-dashboard<br/>TLS: perf-dashboard-tls"]
            sec["Secrets: minio-creds,<br/>perf-secrets, git-creds"]
            runs["Jobs + pods per LoadTest<br/>(runner image)"]
        end
        subgraph miniofs["namespace: minio"]
            minio[("MinIO<br/>bucket perf-results")]
        end
        ingressctl["ingress-nginx"]
    end

    helm_op["helm: charts/loadtest-operator"] --> op & crd
    helm_dash["helm: charts/perf-dashboard"] --> dash & ing
    registry -.-> op & dash & runs
    ingressctl --> ing --> dash
    op --> runs
    runs --> minio
    dash --> minio
    sec -.-> runs & dash
    browser(["Browser"]) -->|"https://perf-dashboard.codelabuk.dev"| ingressctl
```

<sub>Source: [`architecture/06-deployment.mmd`](architecture/06-deployment.mmd) · PlantUML: [`architecture/06-deployment.puml`](architecture/06-deployment.puml)</sub>

| Artifact | Built by | Notes |
|---|---|---|
| `labuk-perf-runner` | `gatling-runner/Dockerfile` (or Jib) | Non-root; heap = 75 % of the container memory limit; extra JDBC drivers in `/opt/perf/drivers` |
| `labuk-loadtest-operator` | `loadtest-operator/Dockerfile` (or Jib) | One or two replicas |
| `labuk-perf-dashboard` | `perf-dashboard/Dockerfile` | Node build stage, Python runtime |
| `charts/loadtest-operator` | Helm | CRD in `crds/` (kept identical to the generated one by the build), RBAC, Lease role, Deployment |
| `charts/perf-dashboard` | Helm | RBAC, Deployment, Service, optional Ingress with TLS |

**Configuration** is environment-based throughout: the runner reads `PLAN`, `WORKER_INDEX` /
`JOB_COMPLETION_INDEX`, `WORKER_COUNT`, `START_AT`, `SHARED_IN`, `RESULTS_DIR`, `METRICS_PORT`, S3 variables
(`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `S3_ENDPOINT`) and any `${NAME}` the plan uses.

**Storage choice on-prem**: either MinIO/S3 (`results.s3`, no shared volume needed — recommended) or a
ReadWriteMany volume such as NFS (`results.pvc`).

---

## 9. Security

| Area | Measure |
|---|---|
| Pods | Run as non-root (uid 1000); dashboard and operator containers drop all capabilities |
| Secrets | Passwords and tokens only through Kubernetes Secrets (`secretRefs`, Git token secret, S3 credentials secret); never in YAML or the LoadTest spec |
| RBAC | Operator: LoadTests, Jobs, Pods (read), Leases in its namespace. Dashboard: LoadTests incl. create/delete, Jobs and Pods read |
| Writes to databases | `sql` update/call/batch steps are refused unless the connection has `allowWrites: true`; `rollback: true` measures writes without keeping them |
| Report serving | Only objects under the results prefix; path traversal rejected |
| Outbound data | Gatling usage analytics disabled |
| **Gap** | The dashboard has **no login** (v1) — keep it behind the ingress / network rules; SSO is on the roadmap |

---

## 10. Key design decisions

| Decision | Why | Consequence |
|---|---|---|
| Open-source Gatling 3.13.1, pinned | No licence cost; required Scala 2.13.15 | Log merger and live reader depend on 3.13.1's log format — re-check on upgrades |
| One generic simulation + YAML | Testers write no code; one engine to maintain | New behaviour = new step type (plugin), not new scripts |
| Own log merger | Gatling 3.13's reports-only mode reads one log | One report and one SLA verdict per distributed run |
| Live metrics by following the log | Gatling OSS has no Graphite/streaming output | Live = report numbers; latency depends on log chunks |
| t-digest summaries | Dashboard must match the HTML report | Same percentiles everywhere |
| Kubernetes Jobs per phase, Indexed workers | Standard objects, worker number for free, clean ownership | Start time fixed at creation (`startDelaySeconds` must cover image pulls) |
| Facts via termination messages | No extra endpoint or shared storage between pods and operator | Facts limited to ~4 KB |
| Polling reconcile (5 s) | Simple, restart-safe; tests run for minutes | Up to 5 s latency between phases |
| Own S3 client (SigV4) | Avoids the large AWS SDK; MinIO-friendly | Access keys only (no IRSA / web identity) |
| SQL on its own thread pool | JDBC blocks | Accurate HTTP timings next to SQL load |
| Dockerfiles alongside Jib | Jib's hand-off to Docker Desktop stalled on the dev laptop | Both paths documented; Dockerfiles verified |

---

## 11. Known limits

- Workers are not yet spread one per node (no anti-affinity / topology spread) — on multi-node clusters
  several workers may share a node.
- `rendezvous` waits per worker; small `throttle` targets are rounded per worker (minimum 1 rps each).
- ConfigMap test sets are flat (no sub-folders) and limited to 1 MB — use Git for real test sets.
- The dashboard reads results from S3 only; runs with `results.pvc` show status and pods but no numbers.
- Oracle-specific behaviour (REF CURSOR, PL/SQL OUT parameters) is implemented but not yet verified on a real
  Oracle database.

---

## Diagram sources

| View | Mermaid | PlantUML |
|---|---|---|
| System context | [01-system-context.mmd](architecture/01-system-context.mmd) | [01-system-context.puml](architecture/01-system-context.puml) |
| Modules | [02-modules.mmd](architecture/02-modules.mmd) | [02-modules.puml](architecture/02-modules.puml) |
| Run lifecycle | [03-run-lifecycle.mmd](architecture/03-run-lifecycle.mmd) | [03-run-lifecycle.puml](architecture/03-run-lifecycle.puml) |
| Results and metrics | [04-results-flow.mmd](architecture/04-results-flow.mmd) | [04-results-flow.puml](architecture/04-results-flow.puml) |
| Engine | [05-engine.mmd](architecture/05-engine.mmd) | [05-engine.puml](architecture/05-engine.puml) |
| Deployment | [06-deployment.mmd](architecture/06-deployment.mmd) | [06-deployment.puml](architecture/06-deployment.puml) |

Render them with GitHub (Mermaid, automatic), [mermaid.live](https://mermaid.live), the IntelliJ / VS Code
Mermaid and PlantUML plugins, or `java -jar plantuml.jar -tsvg docs/architecture/*.puml`.

The PlantUML files share one look from [`_theme.iuml`](architecture/_theme.iuml) (keep it next to them):
blue = control plane, green = run pods, amber = results/storage, red = systems under test,
purple = engine code, sand = test files, grey = build, teal = live metrics. Arrows use the same colours
(control, **load**, results data, dashed live metrics); every diagram has a legend.
