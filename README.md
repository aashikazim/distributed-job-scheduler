# Distributed Job Scheduler

A learning project using Java 21, Spring Boot 3, and PostgreSQL 16.
PostgreSQL will coordinate independent workers using short transactions and leases.
See [AGENTS.md](AGENTS.md) for the design and [progress](docs/PROGRESS.md) for delivery status.

## Local setup

Requires JDK 21, Docker with Compose, and permission to access the Docker daemon.
The Maven Wrapper downloads Maven on first use; no system Maven is needed.

```bash
cp .env.example .env
# Edit .env: set DB_PASSWORD and a random SCHEDULER_API_KEY.
# Generate a key with: python3 -c 'import secrets; print(secrets.token_hex(32))'
set -a
source .env
set +a
docker compose up -d --wait postgres
./mvnw verify
./mvnw spring-boot:run
```

From another terminal:

```bash
curl -fsS http://localhost:8080/actuator/health
```

Expected health status: `UP`. Stop the application with Ctrl-C, and stop PostgreSQL
with `docker compose down` (the named volume preserves data).
Spring Boot does not load `.env` automatically; export it as shown above.
Never commit `.env` or real credentials.

## Persistence

Flyway creates `jobs` and `job_runs` on startup. Hibernate validates those mappings.
Repository tests use an isolated PostgreSQL 16 Testcontainer; `./mvnw verify` requires
Docker access and fails if Docker is unavailable. No external database or `.env` is
needed for those tests: Testcontainers supplies temporary database credentials.

See [learning notes](docs/HOW_IT_WORKS.md) for schema ownership, JSONB, and version locking.
If you were just added to the Docker group, restart your login session to activate it.
For the current session, `sg docker -c './mvnw verify'` applies that membership to the build.
The default Compose database port is 5432. If another project uses it, set
`DB_PORT=15432` and `DB_URL=jdbc:postgresql://localhost:15432/scheduler` in `.env`,
then export `.env` again and run `docker compose up -d --wait postgres`.
A password-authentication error can mean the app reached the other project's database.
Changing the port keeps the projects separate.

## Try the API (Phase 2)

After starting the application as above, run this in another Bash terminal.
Python 3 is used only to read the returned job ID; you can also copy the ID manually.

```bash
set -a; source .env; set +a
BASE=http://localhost:8080/api/v1
JOB_ID=$(curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" -X POST "$BASE/jobs" \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-webhook","jobType":"HTTP","scheduleType":"FIXED_RATE","fixedRateSeconds":60,"payload":{"url":"https://example.com/hook","method":"POST","body":"{}","expectedStatus":[200,202]}}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" "$BASE/jobs?page=0&size=10&type=HTTP&name=demo"
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" "$BASE/jobs/$JOB_ID"
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" -X POST "$BASE/jobs/$JOB_ID/pause"
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" "$BASE/jobs?status=PAUSED"
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" -X POST "$BASE/jobs/$JOB_ID/resume"
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" -X POST "$BASE/jobs/$JOB_ID/trigger"
```

POST returns 201 with a Location header. GET, PUT, and state actions return 200.
DELETE returns 204. The response includes the job's version and nextRunAt.
Lists return content, page, size, totalElements, and totalPages. Page starts at 0;
size is 1–100 (default 20). Filters combine; name is a case-insensitive literal substring.

PUT replaces the definition and requires the latest version. For example:

```bash
VERSION=$(curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" "$BASE/jobs/$JOB_ID" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" -X PUT "$BASE/jobs/$JOB_ID" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"demo-webhook\",\"jobType\":\"HTTP\",\"scheduleType\":\"FIXED_RATE\",\"fixedRateSeconds\":120,\"payload\":{\"url\":\"https://example.com/hook\",\"method\":\"POST\"},\"version\":$VERSION}"
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" -X DELETE "$BASE/jobs/$JOB_ID"
```

The error body is RFC 7807 ProblemDetail: 400 for malformed/invalid fields,
404 for missing jobs, 409 for duplicate names or stale versions, and 422 for invalid
schedule or executor configuration. Validation errors include a fields object.

Schedule rules:

- CRON requires six fields (sec min hour dom month dow) and an IANA timezone, default UTC.
  Spring handles DST: missing local times are skipped, and repeated local times can have two slots.
- FIXED_RATE first becomes due one interval after creation; later schedule calculations
  use their supplied reference time, avoiding a catch-up backlog.
- ONE_TIME uses runAt, including past timestamps for later worker misfire handling.
- Supply only the schedule fields for the selected schedule type.
- Editing a schedule recomputes nextRunAt; other edits preserve it. PUT preserves status.
- Pause retains the due timestamp and repeated pause changes nothing. Resume activates the job,
  resets retries, and recomputes nextRunAt. Trigger activates it, resets retries, and sets
  nextRunAt using PostgreSQL now(), including for paused or terminal jobs.

Payloads must be JSON objects of at most 64 KB, with valid HTTP(S) URLs, methods,
headers, body strings, and expected status codes. Unknown job types return 422.
The HTTP executor and registry are available. API key auth and SSRF validation are enabled.
The default engine polls every 2 seconds with ±20% jitter.

OpenAPI JSON: `/v3/api-docs`. Interactive API documentation: `/swagger-ui.html`.
Run history is available at `/api/v1/jobs/{id}/runs?page=0&size=20` and `/api/v1/runs/{id}`.

## Try the engine (Phase 3)

In a separate terminal, run an empty local HTTP target:

```bash
mkdir -p /tmp/scheduler-webhook-demo
python3 -m http.server 9090 --bind 127.0.0.1 --directory /tmp/scheduler-webhook-demo
```

For this local target, set `SCHEDULER_HTTP_ALLOWED_HOSTS=127.0.0.1` in the application
terminal before starting/restarting it. Private/local destinations are blocked by default.
With the scheduler application running, create an immediately due one-time job:

```bash
set -a; source .env; set +a
BASE=http://localhost:8080/api/v1
JOB_ID=$(curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" "$BASE/jobs" -H 'Content-Type: application/json' \
  -d '{"name":"engine-demo","jobType":"HTTP","scheduleType":"ONE_TIME","runAt":"2020-01-01T00:00:00Z","payload":{"url":"http://127.0.0.1:9090/","method":"GET","expectedStatus":[200]}}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" "$BASE/jobs/$JOB_ID/runs"
curl -fsS -H "X-API-Key: $SCHEDULER_API_KEY" "$BASE/jobs/$JOB_ID"
```

Repeat the last two commands after a poll. The run becomes SUCCEEDED and the job
becomes COMPLETED. For a cron example, replace scheduleType/runAt with
`"scheduleType":"CRON","cronExpression":"*/5 * * * * *"` and use a new name.
Each recurring completion computes its next slot from completion time; long runs do
not build a backlog of missed slots. A live lease prevents another worker claiming
that job, and heartbeat renewals keep long runs reserved.

A second application process may use the same exported database environment and
`SERVER_PORT=8081 ./mvnw spring-boot:run`. Workers have unique HOSTNAME-plus-random IDs.
Use `SCHEDULER_ENABLED=false` to disable polling when inspecting API behavior.
Set SCHEDULER_ROLE to ALL, API, or WORKER as described below.

HTTP supports method, headers, body, expectedStatus, and per-job timeoutSeconds.
Defaults are POST with no body and any 2xx response accepted. Redirects are not followed.
Responses retain at most 4 KB of UTF-8 summary; error records use sanitized failure names.
Executors are Spring JobExecutor beans; the registry rejects unknown types on creation.

While a live lease exists, PUT/DELETE/resume/trigger return 409. Pause remains available;
it prevents future claims and survives completion, while the current run can finish.
Native engine writes advance version, so fetch the current version before editing.

### Delivery semantics and current scope

Delivery is **at-least-once**. A worker can die after the target accepts a request but
before recording success, and a later worker can execute it again after lease expiry.
Targets must implement idempotent handling where duplicate effects matter.
X-Job-Id, X-Job-Run-Id, and X-Job-Attempt identify the job and this execution attempt;
new retries/recoveries get new run IDs, not a stable business idempotency key.

The automated concurrency test runs two independent loops/runners against one PostgreSQL
and verifies 500 jobs, 500 successes, exactly one executor call per job, and both node IDs.
It also verifies capacity limits, SKIP LOCKED, expired lease claims, ownership fencing,
heartbeat renewal, and a cron request to WireMock. Run it directly with:

```bash
./mvnw -Dtest=EngineIntegrationTest verify
```

## Reliability (Phase 4)

A failure gets up to maxRetries additional attempts: maxRetries=2 means at most three
executions. Delays use min(initial × multiplier^retry_count, maximum), then ±10% jitter.
A successful retry resets retry_count. An exhausted one-time job becomes DEAD_LETTER
and keeps its retry count for diagnosis; a recurring job resets retries and advances
to its next slot from completion time. Committed dead letters increment scheduler_dead_letter_total.

Timeouts interrupt the executor and record TIMED_OUT; plugins must honor interruption
and their deadline. Heartbeats renew only the active run's unexpired owner lease.
A reclaimed job fences older RUNNING attempts, including when the node ID is the same.
Starting an old claim snapshot fails, and an old run cannot renew or complete a newer one.

The janitor runs on the poll loop every 30 seconds. It marks expired or replaced-owner
RUNNING rows LOST and deletes finished/lost history older than 30 days, in transactions
of at most 1,000 rows per step. Concurrent janitors skip locked rows. A takeover also
marks the old attempt LOST immediately, without waiting for the next janitor pass.

Misfire behavior uses the agreed 60-second lateness threshold, measured with the database
claim timestamp. FIRE_ONCE_NOW executes once, then advances from completion time.
SKIP advances a stale recurring job without execution, and completes a stale one-time
job without creating a run. It leaves recent due jobs and explicit retries eligible.
Relevant settings (all environment-overridable) are:

```yaml
scheduler:
  misfire-threshold: 60s
  janitor-interval: 30s
  run-retention-days: 30
  shutdown-grace-period: 60s
```

Shutdown first stops claims, waits up to the grace period for active work, then interrupts
unfinished executors and expires/releases this node's leases. Clearing the owner also
fences a heartbeat already in flight. If PostgreSQL is unavailable, shutdown logs the
release failure and recovery falls back to natural lease expiry. Spring's shutdown
phase timeout is 75 seconds, exceeding the default scheduler grace; increase it too
when increasing scheduler.shutdown-grace-period.

Polling catches database errors and exponentially increases its base delay up to 30 seconds
(or the configured poll interval if larger), with ±20% jitter. Recovery resets it to the
normal interval. Pool borrowing, connection establishment, statements, and socket reads
have bounded waits. Startup still requires PostgreSQL for Flyway and schema validation.

Run the reliability and concurrency scenarios with Java 21 and Docker available:

```bash
./mvnw -Dtest=EngineIntegrationTest,PollingResilienceTest,BackoffPolicyTest verify
```

| Tested failure | Observed behavior |
| --- | --- |
| Worker loses execution/heartbeats without completion | Another worker claims after natural expiry; old run LOST, new run SUCCEEDED. |
| Repeated executor failure | Three attempts with growing ~1s/~2s delays, then DEAD_LETTER; committed metric increments. |
| Retry eventually succeeds | Job completes and retry_count resets. Recurring exhaustion instead advances the next slot. |
| Timeout or ownership loss during execution | Executor is interrupted; timeout records TIMED_OUT; lease loss cannot overwrite the new owner. |
| Shutdown with enough grace | In-flight run succeeds and no new jobs are claimed. |
| Shutdown with insufficient grace | Executor is interrupted, lease released immediately, replacement completes the job. |
| Injected database errors | Polling survives and resumes after errors stop; shutdown tolerates release failure. |
| Overdue SKIP jobs | Recurring jobs advance and one-time jobs complete without execution; retries still run. |
| Abandoned/old history | Janitor marks expired/replaced runs LOST and purges in batches, preserving live/recent history. |

The 500-job/two-worker test continues to pass. A separate packaged-application SIGKILL
check also demonstrated recovery: two unique HTTP attempts, one LOST and one SUCCEEDED
run, and the job COMPLETED. This demonstrates why delivery remains at-least-once.

## Observability and security (Phase 5)

API/ALL startup requires a nonempty SCHEDULER_API_KEY; there is no default key or bypass.
Load `.env` in each client terminal and send `X-API-Key: $SCHEDULER_API_KEY`.
The filter compares fixed-size SHA-256 digests with MessageDigest.isEqual, rejects
missing/wrong/duplicate keys with 401, and rejects ambiguous path aliases before public-route
exemptions. Credentials are never included in error responses or diagnostic DTO strings.
Swagger UI and OpenAPI remain public in API/ALL; use the UI's Authorize button to call the API.
Health/info/Prometheus endpoints remain unauthenticated for cluster-internal probing/scraping.

| SCHEDULER_ROLE | REST routes | Job polling/execution |
| --- | --- | --- |
| ALL (default) | API, docs, actuator | Enabled |
| API | API, docs, actuator | Disabled; a housekeeping task refreshes backlog gauges |
| WORKER | Actuator only; API/docs return 404 | Enabled; no API key required |

SSRF checks resolve all A/AAAA addresses at creation/update and again before execution.
Loopback, link-local/cloud metadata, private IPv4, IPv6 unique-local, multicast/unspecified,
and IPv4 transition addresses are rejected. A mixed public/private DNS answer is rejected.
Redirects and system HTTP proxies are disabled. Explicit, case-insensitive exact hosts in
scheduler.http.allowed-hosts override address restrictions for local development and tests;
subdomains/wildcards do not match automatically. Default: empty allowlist, public targets only.
The Java HTTP client performs its own connection resolution: this guard does not pin DNS
answers to sockets. Use network egress restrictions where rebinding-resistant enforcement is needed.

All executor types receive a 64 KB UTF-8 serialized payload limit. The API also limits raw
POST/PUT bodies to 128 KB (413) and JSON nesting to 100 levels. HTTP payload validation remains
in place. Structured Logstash JSON logs include jobId, runId, and nodeId on execution/heartbeat
threads, including virtual threads; MDC scopes restore/clear context afterward. Headers,
keys, URLs/payloads, and response bodies are omitted from diagnostic record representations.

Readiness includes readinessState and the database health check. Flyway must finish before
application startup completes. A DB outage returns 503 from `/actuator/health/readiness`,
while `/actuator/health/liveness` stays 200; restart storms do not help a database outage.
Readiness also fails while the application is refusing traffic during startup/shutdown.

```bash
curl -fsS http://localhost:8080/actuator/prometheus
curl -fsS http://localhost:8080/actuator/health/readiness
curl -fsS http://localhost:8080/actuator/health/liveness
```

| Micrometer name | Meaning |
| --- | --- |
| scheduler_jobs_due | Global ACTIVE jobs due at the last successful DB snapshot, including leased jobs |
| scheduler_lag_seconds | Database-clock age of the oldest due job |
| scheduler_claim_duration | Claim transaction timer, including failed claims |
| scheduler_claimed_total | Successfully claimed job count on this process |
| scheduler_running | Active local run coordinators |
| scheduler_run_duration | Run timer tagged by registered executor type and outcome |
| scheduler_runs_total | Local finished attempts tagged by type/outcome, including LOST attempts |
| scheduler_dead_letter_total | Committed one-time dead-letter transitions on this process |

Worker gauges refresh every poll, even when capacity is full. API pods refresh global backlog
without claiming jobs. Scraping reads cached values and never queries PostgreSQL; during an
outage the gauges retain their last successful snapshot. Do not sum the duplicated global
backlog across pods; use max. Sum local running/counter series as appropriate.
Timers export `_seconds_count`, `_seconds_sum`, and `_seconds_max` series.

[Captured Prometheus output](docs/PROMETHEUS_SAMPLE.txt) comes from the packaged application
with one successful HTTP run, one failed/dead-lettered run, and one in-flight HTTP run.
The verification also stopped/restarted its isolated database: readiness changed 200 → 503 → 200,
liveness stayed 200, and polling recovered. Tests cover authentication, roles, SSRF, health,
payload bounds, metric updates, secret-safe diagnostics, and virtual-thread MDC propagation.

## Docker (Phase 6)

The same image runs API and WORKER roles. Compose starts PostgreSQL, one API, and two
workers by default. Stop a locally running Maven application before binding the same API port.
Export the source revision so image tags/labels identify the build:

```bash
set -a; source .env; set +a
export IMAGE_TAG="$(git rev-parse --short HEAD)"
docker compose up -d --build --wait --scale app-worker=3
curl -fsS http://localhost:8080/actuator/health/readiness
docker compose logs -f app-worker
```

API_PORT defaults to 8080; DB_PORT controls the host database port as before. Container
connections always use postgres:5432, independent of the host's DB_URL/DB_PORT.
The image uses Maven/JDK 21 to build, then Temurin 21 JRE with UID/GID 10001 to run.
Only the app JAR enters the final image. JAVA_OPTS defaults to -XX:MaxRAMPercentage=75.
Compose drops capabilities, prevents privilege escalation, makes the root filesystem
read-only, provides a writable /tmp tmpfs, and allows 90 seconds for graceful termination.
The wrapper's ZIP checksum is preserved by installing unzip in the build stage.
The image build packages code; `./mvnw verify` separately runs the full Docker-backed tests.

Run the isolated destructive smoke scenario (Docker access, Python 3, and Git required):

```bash
./scripts/smoke.sh
```

It creates a fresh Compose project with temporary credentials, API port 18090, DB port
15437, and a JDK-only test receiver on 19091. Override SMOKE_API_PORT/SMOKE_DB_PORT/
SMOKE_TARGET_PORT if occupied. It scales to three workers, creates 100 one-time jobs,
kills the worker executing the first HTTP request, and checks all jobs finish with one
SUCCEEDED record each. The receiver deduplicates effects by job ID (appropriate only
for these one-time jobs). The other 99 jobs must have exactly one HTTP attempt.
The interrupted job must show an abandoned LOST run and a repeated HTTP attempt.
This proves recovery and exactly one receiver effect, not exactly-once scheduler delivery.
The script prints all counts and removes only its own containers/volume on exit.
KEEP_STACK=1 retains that isolated project and prints the mode-600 credentials file path.
The test receiver is an optional smoke profile, built from test classes with no new dependencies;
it is not included in the production app image or its normal Compose services.
