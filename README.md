# labuk-gatling

A YAML-driven performance testing framework on **open-source Gatling** — built to replace JMeter and
LoadRunner for API (and soon SQL) load testing.

**Perf testers write YAML, not code.** One generic engine reads a test plan and runs it. API names,
variables and conditions all live in the YAML; the engine has no business logic in it.

| Module | Status | What it is |
|---|---|---|
| `gatling-api-core` | ✅ v1.1 | The engine: YAML loader + validation, HTTP, auth, data, flow control, load profiles, SLAs, distributed-run support (worker split, synchronised start, setup once, log merge) |
| `gatling-sql` | ✅ v1 | `sql:` steps and `jdbc` data sources — any JDBC database (Oracle and PostgreSQL drivers included) |
| `gatling-runner` | ✅ v1 | The `perf` command (zip for Windows/Linux/macOS) + Docker image config — no Maven needed for testers |
| `loadtest-operator` | ✅ v1 (tested against a mock API server, not yet on a real cluster) | Kubernetes operator (Java, JOSDK + fabric8): one `LoadTest` = validate → setup → N workers → merged report |

Stack: Java 17, Maven 3.9, Scala 2.13.15, Gatling 3.13.1.

---

## The `perf` command (for testers — no Maven)

Build once (framework team): `mvn install` → `gatling-runner/target/perf-1.0-SNAPSHOT.zip`.
Testers unzip it anywhere; only Java 17+ is needed.

```bash
perf validate plans/orders.yaml --workers 4          # check a plan, see the per-worker split
perf run      plans/orders.yaml -DBASE_URL=https://sit.api.example.com
perf run      plans/orders.yaml --scenario checkout --results D:/perf-results
```
(Windows: `perf.cmd ...`; Git Bash works with `perf` too.)

| Command | What it does |
|---|---|
| `perf validate <plan> [--workers N]` | Checks the plan without sending traffic; prints the split per worker |
| `perf run <plan> [options] [-DNAME=value]` | Runs it on this machine; HTML report under `results/` |
| `perf setup <plan> --out shared.json` | Runs only `setup` and saves its shared lists (distributed runs) |
| `perf worker` | One worker of a distributed run; settings from env (`PLAN`, `WORKER_INDEX`/`JOB_COMPLETION_INDEX`, `WORKER_COUNT`, `START_AT`, `SHARED_IN`, `RESULTS_DIR`); writes `worker-<i>.log` |
| `perf report --input <logs> [--output <dir>]` | Merges workers' logs → one report + SLA verdict |

Options for `run` / `worker`: `--scenario a,b`, `--results <dir>`, `--workers N --worker-index i`,
`--start-at <time>`, `--shared-in <file>`, `--no-reports`, `--description <text>`, `-DNAME=value`.
Environment: `PERF_HEAP` (default `2g`), `PERF_JAVA_OPTS`, `JAVA_HOME`.
**Exit codes:** `0` OK · `1` error or invalid plan · `2` SLAs failed — use them in CI.

The zip contains `bin/` (launchers), `lib/` (engine, Gatling, SQL support, Oracle + PostgreSQL drivers),
`drivers/` (drop extra JDBC jars here) and `conf/logback.xml` (logging).

**Docker image** (configured, built with Jib — no Docker daemon needed to build):
```bash
mvn -pl gatling-runner -am package jib:dockerBuild                               # into local Docker
mvn -pl gatling-runner -am package jib:build -Dimage=<registry>/labuk-perf-runner  # push to a registry
docker run --rm -v "$PWD/perf-tests:/tests" labuk-perf-runner:1.0-SNAPSHOT run /tests/plans/orders.yaml
```
The image runs as a non-root user, sizes its heap from the container memory limit (75%), reads extra
drivers from `/opt/perf/drivers/`, and writes worker results to `RESULTS_DIR` (default `/tmp/results` —
mount a volume there).

**Live metrics:** Gatling 3.13's open-source edition cannot stream metrics while a test runs (it only has
console and file output). Each worker prints a summary every 5 seconds to its console / pod log; the full
report comes at the end. A live dashboard is on the roadmap as a design item.

---

## Quick start (framework developers, with Maven)

```bash
# build + unit tests
mvn -pl gatling-api-core test

# demo: a sample plan against a built-in mock API (no network needed)
mvn -pl gatling-api-core test-compile gatling:test -DskipTests \
    -Dgatling.simulationClass=org.codelabuk.perf.it.MockedYamlSimulation

# check a plan without running it (and see how it splits across 4 workers)
mvn -pl gatling-api-core test-compile exec:exec@validate -Dperf.args="D:/perf-tests/plans/orders.yaml --workers 4"

# run YOUR plan
mvn -pl gatling-api-core test-compile gatling:test -DskipTests -Dplan=D:/perf-tests/plans/orders.yaml

# what the Kubernetes operator will do (setup once, 2 workers, merged report), locally against the mock
bash scripts/local-distributed-demo.sh
```

The HTML report path is printed at the end of each run (`gatling-api-core/target/gatling/<run>/index.html`).
The demo plan lives in [`gatling-api-core/src/test/resources/plans`](gatling-api-core/src/test/resources/plans) —
it is the best place to start copying from.

### Run options

| Option | Meaning |
|---|---|
| `-Dplan=path.yaml` | **Required.** The plan to run. Relative paths resolve from where you run `mvn`. (env: `PLAN`) |
| `-Dscenario=a,b` | Run only these scenarios of the plan. (env: `SCENARIO`) |
| `-Dperf.heap=4g` | Load generator memory (default `2g`). Keep it to about half the machine's RAM. |
| `-DNAME=value` | Fills `${NAME}` placeholders in the YAML — environments, secrets. Environment variables work too. |
| `-Dworker.index=0 -Dworker.count=1` | Distributed runs — which slice of the load this JVM runs. (env: `WORKER_INDEX` / `JOB_COMPLETION_INDEX`, `WORKER_COUNT`) |
| `-Dstart.at=...` | Wait until this moment before starting load — epoch millis or ISO-8601 (`2026-11-20T10:00:00Z`). (env: `START_AT`) |
| `-Dmode=setup -Dshared.out=f.json` | Run only the plan's `setup` and write its shared lists to a file. (env: `MODE`, `SHARED_OUT`) |
| `-Dshared.in=f.json` | Skip `setup`; take the shared lists from that file. (env: `SHARED_IN`) |

One run = one plan. A plan can contain several scenarios that run in parallel.
See [Distributed runs](#distributed-runs) for how the last four fit together.

### Validating a plan

```bash
mvn -pl gatling-api-core test-compile exec:exec@validate -Dperf.args="plans/orders.yaml --workers 4 --scenario checkout"
```

Loads the plan exactly like a run would (includes, CSV files, `${...}` placeholders, conditions, SLA targets)
without sending any traffic, then prints how users and data split across the workers and warns when a
`queue` data source would run out. Exit code 0 = valid, 1 = invalid — use it in CI on every merge request.

---

## Organising tests

Keep tests in their own folder or repository — no code in it:

```
perf-tests/
├── apis/          orders.yaml, payments.yaml    # API catalog: each API defined once
├── data/          users.csv, accounts.csv       # test data
├── bodies/        create-order.json             # request bodies (optional)
└── plans/         orders-peak.yaml, payments-soak.yaml
```

All paths inside a plan (`include`, CSV files, body files) resolve **relative to the plan file**.

---

## Writing a plan

```yaml
name: order-flow
baseUrl: ${BASE_URL:https://sit.api.example.com}   # ${NAME:default} - override with -DBASE_URL=...
include: [ ../apis/orders.yaml ]                  # API catalog files

http:                                              # optional
  headers: { X-Client: perf-test }                 # sent on every request
  shareConnections: false                          # true = one connection pool for all users (service-to-service)

dataSources:
  users: { file: ../data/users.csv, strategy: queue }

auth: { type: login, api: login, token: $.token, refreshBefore: 60s }

setup: ...          # optional - runs once before the load
scenarios: [ ... ]  # required - what virtual users do, and how many
sla: [ ... ]        # optional - pass/fail criteria
maxDuration: 30m    # optional - hard stop
```

Unknown or misspelled keys are rejected with the file and exact location, e.g.
`orders.yaml: scenarios[0].steps[2].pollUntil: unknown key 'maxAtempts' - allowed: condition, maxAttempts, ...`

### Variables

- `#{name}` — a **runtime** value from the virtual user's session: a CSV column, something extracted from a
  response, a `forEach` item, a `set` value. Gatling EL functions also work: `#{randomUuid()}`,
  `#{currentTimeMillis()}`, `#{list.size()}`, `#{list.random()}`, `#{map.key}`.
- `${NAME}` / `${NAME:default}` — a **load-time** value from `-DNAME=...` or an environment variable.
  Use it for environments and secrets; never put passwords in YAML.

---

## API catalog

```yaml
apis:
  login:
    request: POST /auth/login                 # or:  method: POST  +  path: /auth/login
    body:
      json: { username: "#{username}", password: "#{password}" }
    checks:
      - status: 200

  getOrder:
    request: GET /orders/#{orderId}
    headers: { X-Correlation-Id: "#{randomUuid()}" }
    query: { expand: items }
    checks:
      - status: [200, 304]
      - jsonPath: $.id
        equals: "#{orderId}"
    extract:                                   # saved into the user's session
      orderStatus: $.status                    # short form = jsonPath, string
      itemIds: { jsonPath: "$.items[*].id", all: true }
```

**Body** — one of:
`json:` (inline YAML or a JSON string), `file: ../bodies/x.json`, `raw: "<xml/>"` + `contentType:`,
`form: { field: value }`. All may contain `#{...}`.

**Checks** — a failed check marks the request KO in the report:

| Check | Example |
|---|---|
| `status` | `status: 200` or `status: [200, 201]` (default when no status check: 2xx/304) |
| `jsonPath` | `jsonPath: $.id` (exists) · `equals: "x"` · `notExists: true` |
| `regex` / `header` | `regex: 'id="(\d+)"'` · `header: Location` (+ `equals` / `notExists`) |
| `bodyContains` | `bodyContains: SUCCESS` (+ `notExists: true`) |

**Extract** — `name: $.path` or `name: { jsonPath | regex | header: ..., all: true, optional: true, type: ... }`.
`type` (jsonPath only): `string` (default), `int`, `long`, `number`, `boolean`, `object`, `list`, `any`.
Without `optional: true`, a missing value fails the request.

A step can add `checks` / `extract` on top of the catalog's; a step extract with the same name replaces the
catalog one.

---

## Steps

| Step | Example | Notes |
|---|---|---|
| `api` | `- api: getOrder` | Optional `name:` (report label), `extract:`, `checks:` |
| `transaction` | `- transaction: checkout`<br>`  steps: [ ... ]` | Timed group in the report (LoadRunner transaction) |
| `wait` / `thinkTime` | `- wait: 2s` · `- wait: 1s..3s` · `- wait: { duration: "#{waitSeconds}s", max: 5m }` | Can come from a response; `max` caps it |
| `pace` | `- pace: 10s` | Inside loops: each iteration takes at least this long (LoadRunner pacing) |
| `pollUntil` | see below | Repeat steps until a condition holds; KO if it never does |
| `forEach` | `- forEach: { list: itemIds, as: itemId, steps: [ ... ] }` | Loop over an extracted list |
| `repeat` | `- repeat: { times: 5, steps: [ ... ] }` | `times` may be `"#{n}"` |
| `loop` | `- loop: { during: 10m, steps: [ ... ] }` | Keep iterating for a duration (closed-model soak tests) |
| `if` | `- if: { condition: "#{status} == 'OPEN'", then: [ ... ], else: [ ... ] }` | |
| `randomSwitch` | `- randomSwitch: [ { percent: 70, steps: [...] }, { percent: 30, steps: [...] } ]` | Traffic mix |
| `retry` | `- retry: { times: 3, steps: [ ... ] }` | Retries the block if anything in it fails |
| `rendezvous` | `- rendezvous: 50` | Users wait until 50 arrive, then go together (per worker when distributed) |
| `set` | `- set: { channel: WEB, runId: "#{randomUuid()}" }` | |
| `feed` | `- feed: accounts` | Take the next record mid-flow |
| `log` | `- log: "order #{orderId} is #{orderStatus}"` | Printed to the console |
| `stopUserIfFailed` | `- stopUserIfFailed: true` | End this virtual user if anything failed so far |

### pollUntil

```yaml
- pollUntil:
    condition: "#{status} != 'PENDING'"
    maxAttempts: 10        # at least one of maxAttempts / timeout is required
    timeout: 15m
    failMessage: request never left PENDING      # optional - shown in the report's Errors table
    name: time-to-complete                       # optional - see below
    steps:
      - api: getWaitTime
        extract: { waitSeconds: $.waitSeconds }
      - wait: { duration: "#{waitSeconds}s", max: 5m }
      - api: getStatus
        extract: { status: $.status }
```

The condition is checked **before** each attempt, so extract the first value before the poll.
If it is still false after `maxAttempts`/`timeout`, the report gets a KO entry (named `pollUntil`, or the
poll's `name`) with the `failMessage` in its Errors table, and the enclosing transaction turns KO.
With a `name`, every poll also records an OK entry whose response time is **how long the condition took to
become true** — usable in SLAs: `api.time-to-complete.responseTime.p95 < 2m`.
Waiting users cost almost nothing — 50k users waiting a minute do not tie up threads.

### Conditions

Used by `pollUntil` and `if`:

```
#{status} != 'PENDING'
#{retries} < 5 and not #{done}
exists #{token}
#{roles} contains 'admin' or (#{code} == 200 && #{items.size()} > 0)
```

Operators: `==  !=  <  <=  >  >=  contains`, `and`/`&&`, `or`/`||`, `not`/`!`, `exists`, parentheses.
Numbers compare numerically, everything else as text. A bare `#{flag}` is true unless missing, empty,
`false` or `0`.

### Durations

`500ms`, `2s`, `1.5s`, `10m`, `1h`, `1m30s`, `30` (= seconds), ranges `1s..3s` (random in between).

---

## Data

```yaml
dataSources:
  users:    { file: ../data/users.csv, strategy: queue }
  products: { file: ../data/products.csv, strategy: random, separator: ";" }
```

CSV with a header row; columns become `#{column}` variables.

| `strategy` | Behaviour |
|---|---|
| `circular` (default) | In order, wrapping around |
| `queue` | Each record once — when it runs out, further users stop (a warning is printed up front if the file is too small for the planned load) |
| `random` | Random record each time |
| `shuffle` | Shuffled once, then like `queue` |

`splitAcrossWorkers: true` (default) gives each worker a different slice in distributed runs, so e.g. no
login is used by two pods.

A scenario takes one record from each feeder at its start: `feeders: [ users, products ]`.

### Setup and shared lists

`setup` runs **once** before the scenarios start — e.g. fetch a list that virtual users then work through:

```yaml
setup:
  vars: { username: ${SETUP_USER}, password: ${SETUP_PASSWORD} }   # account used for setup
  steps:
    - api: loadFilterData
      extract:
        srList: { jsonPath: "$.items[*].srId", all: true }
  share:
    srQueue: { from: srList, as: sr }      # each user gets one item as #{sr}

scenarios:
  - name: process-items
    feeders: [ users, srQueue ]
```

If list items are JSON objects (`type: object`), their fields become variables directly. `strategy`
defaults to `queue` for shares. If setup fails, the test stops immediately — it cannot run without its data.

---

## Authentication

Set once at plan level (applies to setup and every scenario); a scenario can override it or use
`auth: none`. The token is added to every request automatically (except the login call) — catalog APIs do
not need an `Authorization` header.

```yaml
# 1. call a login API (credentials from the users CSV) and refresh before the JWT expires
auth: { type: login, api: login, token: $.token, refreshBefore: 60s }
#    optional: saveAs: token, header: Authorization, prefix: "Bearer ", ttl: 15m (if the token is not a JWT)

# 2. mint a signed JWT locally (environments that trust a test secret) - no login traffic
auth:
  type: jwt
  secret: ${JWT_SECRET}
  secretEncoding: text          # or base64
  algorithm: HS256              # HS384, HS512
  claims: { sub: "#{username}", roles: tester }
  validity: 1h

# 3. a fixed token / API key
auth: { type: static, value: ${API_KEY}, header: X-Api-Key, prefix: "" }
```

---

## Load

```yaml
scenarios:
  - name: browse
    load:
      profile:
        - rampUsersPerSec: { from: 1, to: 100, during: 5m }
        - constantUsersPerSec: { rate: 100, during: 30m, randomized: true }
    throttle:                   # optional cap on requests/sec (LoadRunner goal-oriented)
      - reachRps: 500
        in: 1m
      - holdFor: 30m
```

**Open model** — new users arrive at a rate (APIs, websites): `atOnceUsers`, `rampUsers {users, during}`,
`constantUsersPerSec {rate, during}`, `rampUsersPerSec {from, to, during}`, `stressPeakUsers {users, during}`,
`incrementUsersPerSec {by, times, eachLevelLasting, separatedByRampsLasting, startingFrom}`, `nothingFor`.
**Closed model** — a fixed number of concurrent users (call centres, soak tests with `loop`):
`constantConcurrentUsers {users, during}`, `rampConcurrentUsers {from, to, during}`,
`incrementConcurrentUsers {by, times, eachLevelLasting, separatedByRampsLasting, startingFrom}`.
A profile cannot mix the two. Short forms also work: `- constantConcurrentUsers: 200` + `during: 10m`.

**Rates** take units: `5` or `5/s`, `266/min`, `16000/h`. A rate counts **virtual users starting the
scenario**, not HTTP requests — a scenario with 3 API calls at `100/min` sends ~300 requests/min.

In distributed runs all numbers are **totals** — each worker runs its share automatically.

### From JMeter: "266 requests/min, ~16k in an hour, load increased in steps"

```yaml
# one request per virtual user -> rate = requests
load:
  profile:
    - incrementUsersPerSec: { by: 66/min, times: 3, eachLevelLasting: 5m, separatedByRampsLasting: 1m, startingFrom: 66/min }
    - constantUsersPerSec: { rate: 266/min, during: 1h }       # ~15,960 requests
```

The same as JMeter's "N threads + Constant Throughput Timer" (closed model + pacing):

```yaml
steps:
  - loop:
      during: 1h
      steps:
        - pace: 4.5s                 # each user starts an iteration at most every 4.5s
        - api: getOrder
load:
  profile:
    - constantConcurrentUsers: { users: 20, during: 1h }    # 20 users / 4.5s = ~266/min
```

`throttle` can also cap the total rate, but only in whole requests/second per worker.

### Sizing a load generator

One machine handles thousands of concurrent users — measured on a 4-CPU, 16 GB laptop with `-Dperf.heap=4g`:
**5,000 concurrent users, 62k requests at ~650 req/s, 0 errors, p95 7 ms** (against a mock on the same
machine). Rough guide:

- Memory grows with concurrent users and response sizes; start with `-Dperf.heap=4g` on an 8 GB machine.
- Watch the load generator's CPU: above ~70-80% its own timings get less accurate — add a worker instead.
- Every virtual user opens its own connections (like real users), so behind a load balancer the traffic
  spreads across nodes the way real traffic does; `JSESSIONID` and other cookies are kept per user, so
  sticky sessions behave normally. Set `http.shareConnections: true` only for service-to-service APIs.
- Windows limits outgoing ports (~16k by default); very high new-connection rates from one machine can run
  out — use more workers or longer keep-alive.

---

## SLAs

```yaml
sla:
  - global.failedRequests.percent < 1
  - global.responseTime.p95 < 800ms
  - api.getOrder.responseTime.p99 < 1.5s
  - transaction.checkout.failed.percent < 2
  - global.requestsPerSec >= 200
```

`<scope>.<metric> <op> <value>` — scope `global`, `api.<name>`, `transaction.<name>`;
metrics `responseTime.{min,max,mean,stdDev,p50,p75,p90,p95,p99,p999}`,
`{failed,successful,all}Requests.{percent,count}` (short: `failed.percent`), `requestsPerSec`;
ops `< <= > >= ==`. Results print at the end of the run, and a failed SLA fails the build — use it in CI.

---

## SQL load testing (`gatling-sql`)

Plans that use `sql:` steps run from the **`gatling-sql`** module (it contains the core, the SQL support and
the Oracle + PostgreSQL drivers); everything else in this README works there too:

```bash
mvn -pl gatling-sql test-compile gatling:test -DskipTests -Dplan=plans/orders-db.yaml -DDB_PASSWORD=...
# demo: database load + API-then-database flow, against in-memory Derby and the mock API
mvn -pl gatling-sql test-compile gatling:test -Dgatling.simulationClass=org.codelabuk.perf.sql.MockedSqlSimulation
```

### Connections and DB test data

```yaml
dataSources:
  ordersDb:
    type: jdbc
    url: ${DB_URL}                     # jdbc:oracle:thin:@//host:1521/SERVICE, jdbc:postgresql://host/db, ...
    user: ${DB_USER}
    password: ${DB_PASSWORD}
    pool: { size: 20, connectionTimeout: 10s }   # all connections are opened before the load starts
    allowWrites: false                 # default: only SELECTs; set true for update/call steps
    maxSessions: 200                   # optional: warns if pool size x workers would exceed it
    fetchSize: 500                     # optional
    properties: { oracle.jdbc.ReadTimeout: "60000" }   # optional driver properties
    # driver: oracle.jdbc.OracleDriver  # optional - normally detected from the URL

  activeCustomers:                     # rows of a query as test data (one row per virtual user)
    type: jdbc
    datasource: ordersDb
    query: "SELECT customer_id, account_no FROM customers WHERE status = ? ORDER BY customer_id"
    params: [ ACTIVE ]
    strategy: queue                    # same strategies as CSV; split across workers by default
```

An unreachable database stops the run at start-up with `Cannot connect to jdbc connection '...'`.
Column names are lower-case in variables (`#{customer_id}`), whatever the database returns.

### The `sql` step

```yaml
- sql:
    datasource: ordersDb
    name: find-orders                  # label in the report (default "sql <datasource>") - usable in SLAs
    query: "SELECT id, status FROM orders WHERE customer_id = ?"      # or update: / call:
    params: [ "#{customer_id}" ]
    checks:
      - rowCount: ">= 1"
      - column: status
        equals: OPEN
    extract:
      orderId: id                      # first row, column id
      allIds: { column: id, all: true }
      orderRows: { rows: true }        # all rows as a list (use with forEach / setup share)
      found: { count: true }           # number of rows (or rows updated)
    timeout: 5s                        # optional query timeout
    maxRows: 1000                      # default 1000
```

| Statement | Use | Notes |
|---|---|---|
| `query:` | `SELECT` / `WITH` | Read-only; checks `rowCount`, `column` + `equals` |
| `update:` | `INSERT` / `UPDATE` / `DELETE` / DDL | Needs `allowWrites: true`; check `updateCount` |
| `call:` | Stored procedures `{call pkg.proc(?, ?)}` | Needs `allowWrites: true`; OUT params, result rows |

**Parameters**, one per `?`: a literal (`42`, `ACTIVE`), a variable (`"#{orderId}"`), a typed value
`{ value: "19.99", type: decimal }` (types: `string int long decimal double boolean date timestamp null`), or
for procedures an OUT parameter `{ out: int, saveAs: newId }` (out types add `number` and `cursor` for
Oracle REF CURSOR, saved as a list of rows).

**`rollback: true`** runs a write and rolls it back: the timing is real, nothing is kept — useful against
shared test data.

**`reportConnectionWait: true`** adds a `<name> (connection)` entry timing how long the step waited for a
free connection. If that is high, the pool is too small; if the statement itself is slow, it is the database.

How it runs: JDBC calls block, so each connection pool has its own threads (pool size = threads); Gatling's
own threads are never blocked. The measured time includes waiting for a free connection, as a real
application would experience it.

Other databases: add the driver as a dependency in `gatling-sql/pom.xml` (SQL Server, MySQL, DB2, ...) —
no code change.

---

## Distributed runs

One machine runs thousands of virtual users, but bigger tests run the same plan on several workers
(pods, later started by the Kubernetes operator). Every load number in a plan is a **total**; each worker
runs its share. The steps below are what the operator automates — `scripts/local-distributed-demo.sh` runs
them on one machine against the mock API.

1. **Setup once** — `-Dmode=setup -Dshared.out=shared.json` runs only the plan's `setup` and writes its
   shared lists to a file.
2. **Workers** — each gets `-Dworker.index=i -Dworker.count=N -Dshared.in=shared.json -Dstart.at=<same time>`:
   - users, rates and `throttle` targets are divided by N;
   - CSV rows and shared lists are split so no two workers get the same item (lists are sorted first, so
     every worker splits an identical list);
   - all workers wait for `start.at`, so the load ramps up together.
3. **Merge** — collect every worker's `simulation.log` into one folder, then
   `mvn -pl gatling-api-core exec:exec@merge -Dperf.args="--input <folder> --output <results-folder>"`
   builds **one** HTML report and evaluates the SLAs on all the data. Exit code 0 = SLAs met, 2 = failed.

A worker's own SLA printout covers its slice only and is informational — run workers with
`-Dgatling.failOnError=false`; the merged result decides.

Good to know:
- `rendezvous: 50` waits for 50 users **per worker** (each worker waits for its share).
- Small `throttle` targets split badly — 3 rps over 10 workers becomes 1 rps each (a warning is printed).
- Without `shared.in`, each worker runs `setup` itself; that still works (lists are sorted before
  splitting) but calls the setup APIs N times.
- Log merging is tied to Gatling's log format; it is supported for the pinned Gatling 3.13.1 and refuses
  logs from other versions.

---

## On Kubernetes (`loadtest-operator`)

The operator automates the distributed steps above. Install once:

```bash
mvn install                                                    # builds everything, generates the CRD
kubectl apply -f loadtest-operator/target/classes/META-INF/fabric8/loadtests.perf.codelabuk.org-v1.yml
mvn -pl gatling-runner,loadtest-operator -am package jib:build -Dimage=...   # push both images (see each pom)
kubectl apply -f loadtest-operator/deploy/operator.yaml        # namespace perf-system, RBAC, operator
```

Then each test run is one resource (full example: `loadtest-operator/deploy/example-loadtest.yaml`):

```yaml
apiVersion: perf.codelabuk.org/v1alpha1
kind: LoadTest
metadata: { name: orders-peak-0412, namespace: perf }
spec:
  image: registry.example.com/labuk-perf-runner:1.0.0
  plan:
    git: { repo: https://git.example.com/qa/perf-tests.git, ref: main, tokenSecret: { name: git-creds } }
    file: plans/orders-peak.yaml
  workers: 5
  env: { BASE_URL: https://sit.api.example.com }
  secretRefs: [ perf-secrets ]                 # passwords/tokens as ${...} values
  resources: { limits: { cpu: "2", memory: 4Gi } }
  results: { pvc: perf-results }               # existing claim; ReadWriteMany on multi-node clusters
```

```bash
kubectl get loadtests -n perf -w           # phase: Validating, Setup, Running, Aggregating, Completed / Failed
kubectl get lt orders-peak-0412 -n perf -o jsonpath='{.status.result} {.status.reportPath} {.status.message}'
```

What happens:

| Phase | Kubernetes Job | Notes |
|---|---|---|
| Validating | `<name>-validate` runs `perf validate` | A bad plan fails here with the exact YAML error in `status.message` |
| Setup | `<name>-setup` runs `perf setup` | Only if the plan has `setup.share`; runs once |
| Running | `<name>-workers` — an Indexed Job with N pods | All start at `status.startAt` (now + `startDelaySeconds`); no retries, a crashed worker fails the run |
| Aggregating | `<name>-report` runs `perf report` | Merged report on the results volume; `status.result` = `Passed` / `SLAFailed` |

Tests come from **Git** (cloned by an init container, token from a Secret), a **ConfigMap** (small flat test
sets, 1 MB limit) or **the image** (`inImage: true`, absolute `file`). Pods run as non-root; Jobs are owned
by the LoadTest (deleting it deletes them) and are cleaned up `ttlSecondsAfterFinished` after finishing.
Create a new LoadTest (new name) for every run — a finished one is never re-run.

---

## LoadRunner / JMeter → this framework

| LoadRunner | JMeter | Here |
|---|---|---|
| Vuser script | Thread group | `scenario` |
| `lr_start/end_transaction` | Transaction controller | `transaction` |
| Parameter file | CSV Data Set | `dataSources` + `feeders` |
| `web_reg_save_param` (correlation) | Extractors | `extract` |
| Think time / pacing | Timers | `wait` / `pace` |
| `lr_rendezvous` | Synchronizing timer | `rendezvous` |
| Controller schedule / goal-oriented | Thread group schedule / throughput timer | `load` / `throttle` |
| SLA | Assertions + plugins | `sla` |
| Load generators | Master/slave | Workers + merge ([Distributed runs](#distributed-runs)) → Kubernetes operator (planned) |

---

## Troubleshooting

- **`"${X}" is not set`** — pass `-DX=...` or set environment variable `X`, or give a default `${X:value}`.
- **Every request after login fails with 401** — check `auth.token` points at the token in the login
  response; `- log: "#{token}"` after a login helps.
- **Users stop immediately** — a `queue` data source / share ran out; the warning at start-up says how many
  users it can serve.
- **See request/response details for failures** — in `logback-test.xml` set
  `io.gatling.http.engine.response` to `TRACE` (failed requests) or `DEBUG` (all requests).

## For framework developers

Engine code: `gatling-api-core/src/main/scala/org/codelabuk/perf/`

| Package | Responsibility |
|---|---|
| `load` | YAML → model, placeholders, validation |
| `model` | The plan model (no Gatling types) |
| `expr` | Conditions and durations |
| `data` | CSV, record sources, worker slicing, setup shares |
| `build` | Model → Gatling chains, auth, load, SLAs |
| `sim` | `YamlSimulation` — the single generic simulation |
| `cli` | `Validate` and `Merge` command-line tools |
| `results` | `LogMerger` — combines workers' `simulation.log` files |
| `plugin` | Extension points: `StepPlugin`, `DataSourcePlugin` (via `META-INF/services`) |

A new step kind or protocol is a plugin module on the classpath; existing plans keep working.
`gatling-sql` is the reference example: `SqlStepPlugin` (the `sql:` step) and `JdbcDataSourcePlugin`
(`type: jdbc`), registered in `src/main/resources/META-INF/services/`.
