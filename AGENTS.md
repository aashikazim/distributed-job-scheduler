# AGENTS.md: Distributed Job Scheduler

> Instructions for the AI coding agent building this project. Read this whole file before writing code. Follow the phases in order. When something here is ambiguous, ask before guessing.

---

## 1. Project goal

Build a **horizontally scalable, containerized job scheduler** in Java that:

- Stores job definitions in **PostgreSQL** and runs them on a schedule (cron, one-time, fixed-rate).
- Runs as **N identical stateless pods**. Pods coordinate only through Postgres (no Redis, no ZooKeeper, no leader election).
- Is packaged as a **Docker image** and deployed on **Kubernetes**, scaling up and down safely at any time.
- Is deployable to any cloud (EKS, GKE, AKS) with managed Postgres (RDS, Cloud SQL, Azure Database for PostgreSQL).
- Executes jobs through **pluggable executors** (HTTP webhook first; others later).

**The owner wants to learn how it works.** This is a learning project with a 2-3 day timeline. Prefer clear, readable code over clever code. After each phase, add a short "how it works" note (see §12).

### Non-goals (do NOT build these)
- No UI. REST API only.
- No Quartz / db-scheduler / Spring `@Scheduled` for the job engine. We build the engine ourselves. (`@Scheduled` is fine for housekeeping such as purging old runs, if needed.)
- No Redis, Kafka, or other infrastructure beyond Postgres.
- No multi-tenant / user-management system.
- No shell-execution executor in the default build (security risk). See §9.

---

## 2. Tech stack (fixed; do not substitute without asking)

| Concern | Choice |
|---|---|
| Language | Java 21 (use records, `var`, virtual threads where sensible) |
| Framework | Spring Boot 3.3+ |
| Build | Maven with the Maven Wrapper (`./mvnw`) |
| DB | PostgreSQL 16 |
| Migrations | Flyway (`flyway-core` + `flyway-database-postgresql`) |
| Data access | Spring Data JPA for CRUD API; `JdbcClient`/`JdbcTemplate` with native SQL for the scheduler engine |
| Validation | `spring-boot-starter-validation` |
| API docs | `springdoc-openapi-starter-webmvc-ui` |
| Observability | Spring Boot Actuator + Micrometer Prometheus registry |
| Tests | JUnit 5, AssertJ, Testcontainers (PostgreSQL), Awaitility, WireMock |
| Container | Multi-stage Dockerfile, Eclipse Temurin 21 JRE, non-root user |
| Orchestration | Kubernetes manifests via **Kustomize** (base + overlays) |
| CI | GitHub Actions |

Avoid Lombok (use records and plain classes). Add no dependency outside this list without explaining why first.

Base package: `com.example.jobscheduler` (change if the owner specifies otherwise).

---

## 3. Architecture overview

```
            +------------------+
 clients -> |  REST API (role) |--+
            +------------------+  |        +-----------------+
                                  +------->|   PostgreSQL    |
            +------------------+  |        |  jobs, job_runs |
            | Scheduler Worker |--+        +-----------------+
            | poll -> claim -> |
            | execute -> record|
            +------------------+
   (every pod runs the same image; role chosen by config)
```

### Roles (config-driven, same image)
`scheduler.role` = `ALL` (default, local dev) | `API` | `WORKER`.
- `API`: serves REST only; the polling loop is disabled.
- `WORKER`: runs the polling loop and executors; REST is limited to actuator endpoints.
- `ALL`: both.

Implement with `@ConditionalOnProperty` / Spring profiles. This lets API and workers scale independently in Kubernetes.

### Core algorithm (the heart of the project)

Every worker runs a polling loop every `scheduler.poll-interval` (default 2s, with random jitter ±20% to avoid thundering herd):

1. **Claim** due jobs atomically in ONE short transaction (never hold row locks while a job executes):

```sql
UPDATE jobs
SET locked_by = :nodeId,
    locked_until = now() + make_interval(secs => :leaseSeconds),
    updated_at = now()
WHERE id IN (
    SELECT id FROM jobs
    WHERE status = 'ACTIVE'
      AND next_run_at <= now()
      AND (locked_until IS NULL OR locked_until < now())
    ORDER BY next_run_at
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

   `FOR UPDATE SKIP LOCKED` means two pods never claim the same row. The lease (`locked_until`) makes the claim survive the transaction and expire if the pod dies.

2. **Submit** each claimed job to a bounded thread pool (virtual threads allowed, but cap concurrency with a `Semaphore` set by `scheduler.max-concurrent-runs`). Only claim as many jobs as there is free capacity (`min(batchSize, freeSlots)`), never claim what you cannot start.

3. **Execute** with a per-job timeout. Insert a `job_runs` row (`RUNNING`) at start; update it at the end. While running, a **heartbeat** extends `locked_until` every `lease/3` seconds, but only if `locked_by` still equals this node (compare-and-set).

4. **Complete** in one transaction:
   - Success: reset `retry_count`, set `last_run_at`, compute the next `next_run_at` (cron/fixed-rate) or set status `COMPLETED` (one-time), clear the lock.
   - Failure: if `retry_count < max_retries`, set `next_run_at = now() + backoff(retry_count)` and increment `retry_count`; otherwise set status `DEAD_LETTER` for one-time jobs, or advance to the next schedule slot for recurring jobs and record the failure. (Document the chosen behavior in code comments and tests.)
   - Completion updates must include `WHERE id = :id AND locked_by = :nodeId` so a node that lost its lease cannot overwrite newer state.

5. **Recovery**: no special recovery process is required. When a pod dies, its leases expire, and any worker's claim query picks the job up again. A janitor step (in the same loop, every ~30s) marks `job_runs` rows that are `RUNNING` with an expired owner lease as `LOST`.

### Delivery semantics
**At-least-once.** A job may run twice (e.g., pod dies after executing but before recording). Therefore:
- Every execution gets a unique `run_id` passed to the executor (HTTP: header `X-Job-Run-Id`, plus `X-Job-Id` and `X-Job-Attempt`) so targets can deduplicate.
- Document this in the README.

### Time rules
- All timestamps are `timestamptz`; use `Instant` in Java.
- Use the **database clock** (`now()`) for claim and lease decisions, never the pod clock, to avoid clock skew between pods.
- Cron expressions use Spring's `CronExpression` (6 fields: sec min hour dom month dow) with a per-job IANA timezone (default `UTC`). Handle DST via the library; add a test for a DST transition.

### Misfire policy (job was due long ago, e.g., all workers were down)
Per-job `misfire_policy`:
- `FIRE_ONCE_NOW` (default): run once immediately, then compute next from now.
- `SKIP`: do not run; compute next from now.

### Overlap policy (v1)
A job never runs concurrently with itself: the lease on the job row guarantees this. If a run exceeds its schedule interval, the next slot is simply computed from completion time. Document it.

---

## 4. Data model (Flyway migrations in `src/main/resources/db/migration`)

Migration files are named `V1__create_jobs.sql`, `V2__create_job_runs.sql`, etc. **Never edit an applied migration; add a new one.**

```sql
CREATE TABLE jobs (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL UNIQUE,
    description           TEXT,
    job_type              VARCHAR(50)  NOT NULL,          -- HTTP, ...
    payload               JSONB        NOT NULL,          -- executor-specific config
    schedule_type         VARCHAR(20)  NOT NULL,          -- CRON | ONE_TIME | FIXED_RATE
    cron_expression       VARCHAR(100),
    fixed_rate_seconds    INTEGER,
    run_at                TIMESTAMPTZ,                    -- for ONE_TIME
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    status                VARCHAR(20)  NOT NULL,          -- ACTIVE | PAUSED | COMPLETED | DEAD_LETTER
    next_run_at           TIMESTAMPTZ,
    last_run_at           TIMESTAMPTZ,
    max_retries           INTEGER      NOT NULL DEFAULT 3,
    retry_count           INTEGER      NOT NULL DEFAULT 0,
    backoff_initial_secs  INTEGER      NOT NULL DEFAULT 10,
    backoff_multiplier    NUMERIC(4,2) NOT NULL DEFAULT 2.0,
    backoff_max_secs      INTEGER      NOT NULL DEFAULT 3600,
    timeout_seconds       INTEGER      NOT NULL DEFAULT 60,
    misfire_policy        VARCHAR(20)  NOT NULL DEFAULT 'FIRE_ONCE_NOW',
    locked_by             VARCHAR(100),
    locked_until          TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Partial index: the claim query only scans active, due jobs
CREATE INDEX idx_jobs_due ON jobs (next_run_at) WHERE status = 'ACTIVE';

CREATE TABLE job_runs (
    id             UUID PRIMARY KEY,
    job_id         UUID        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt        INTEGER     NOT NULL,
    status         VARCHAR(20) NOT NULL,   -- RUNNING | SUCCEEDED | FAILED | TIMED_OUT | LOST
    scheduled_for  TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    node_id        VARCHAR(100) NOT NULL,
    result_summary TEXT,                   -- truncated to 4 KB
    error_message  TEXT                    -- truncated to 4 KB
);
CREATE INDEX idx_job_runs_job_started ON job_runs (job_id, started_at DESC);
```

`node_id` = pod name (`HOSTNAME` env var) plus a short random suffix generated at startup.

Housekeeping: delete `job_runs` older than `scheduler.run-retention-days` (default 30), in batches, off-peak-safe.

---

## 5. REST API (`/api/v1`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/jobs` | Create job (validate cron, timezone, payload per job type); compute initial `next_run_at` |
| GET | `/jobs` | List with pagination (`page`, `size`) and filters (`status`, `type`, `name` contains) |
| GET | `/jobs/{id}` | Get one |
| PUT | `/jobs/{id}` | Update (recompute `next_run_at` if schedule changed; optimistic locking via `version`) |
| DELETE | `/jobs/{id}` | Delete |
| POST | `/jobs/{id}/pause` | Status `PAUSED` |
| POST | `/jobs/{id}/resume` | Status `ACTIVE`, recompute `next_run_at` |
| POST | `/jobs/{id}/trigger` | Run now: set `next_run_at = now()` (do not execute inline in the request thread) |
| GET | `/jobs/{id}/runs` | Paginated run history |
| GET | `/runs/{id}` | One run |

Rules:
- Request/response DTOs are **records**; never expose JPA entities.
- Errors use RFC 7807 `ProblemDetail` through a `@RestControllerAdvice`. Validation errors list the offending fields.
- Return 404 for unknown ids, 409 on optimistic-lock conflict or duplicate name, 422 for invalid cron/timezone.
- OpenAPI docs are served at `/swagger-ui.html`.
- Auth (Phase 5): API key in `X-API-Key` header, compared in constant time against `SCHEDULER_API_KEY`. Actuator health/prometheus endpoints stay open (they are cluster-internal).

Example create request:
```json
{
  "name": "nightly-report",
  "jobType": "HTTP",
  "scheduleType": "CRON",
  "cronExpression": "0 0 2 * * *",
  "timezone": "America/New_York",
  "payload": {
    "url": "https://example.com/hooks/report",
    "method": "POST",
    "headers": {"Content-Type": "application/json"},
    "body": "{\"report\":\"nightly\"}",
    "expectedStatus": [200, 202]
  },
  "maxRetries": 3,
  "timeoutSeconds": 30
}
```

---

## 6. Executors (plugin model)

```java
public interface JobExecutor {
    String type();                                   // e.g. "HTTP"
    void validatePayload(JsonNode payload);          // throws on invalid config at create time
    ExecutionResult execute(ExecutionContext ctx);   // throws on failure; honors ctx.deadline()
}
```
- Spring discovers all `JobExecutor` beans; an `ExecutorRegistry` maps `type()` to a bean. Unknown type is rejected at creation with 422.
- `ExecutionContext` carries `jobId`, `runId`, `attempt`, `payload`, `deadline`.
- **HTTP executor (required in v1):** use `java.net.http.HttpClient`; support method, headers, body, expected status codes, the job's timeout, and the `X-Job-*` headers from §3.
  - **SSRF protection (required):** by default, block requests to loopback, link-local (including `169.254.169.254` cloud metadata), and private ranges. Resolve DNS and check the resolved IP. Allow overriding via `scheduler.http.allowed-hosts` for local development and tests.
  - Truncate the stored response body to 4 KB. Never log secrets or headers named `Authorization`/`X-API-Key`.
- **Stretch executors (only after Phase 8, each behind a feature flag, off by default):** SQL (read-only allowlisted statements), message queue publish, email. A shell executor is explicitly excluded.

---

## 7. Configuration (`application.yml`, all overridable by env vars)

```yaml
scheduler:
  role: ALL                    # ALL | API | WORKER
  poll-interval: 2s
  batch-size: 20
  max-concurrent-runs: 50
  lease-duration: 60s
  heartbeat-interval: 20s
  shutdown-grace-period: 60s
  run-retention-days: 30
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/scheduler}
    username: ${DB_USER:scheduler}
    password: ${DB_PASSWORD:scheduler}
    hikari:
      maximum-pool-size: 10
  jpa:
    open-in-view: false
    hibernate.ddl-auto: validate    # Flyway owns the schema
server:
  shutdown: graceful
management:
  endpoint.health.probes.enabled: true
  endpoints.web.exposure.include: health,info,prometheus
```
Secrets (DB password, API key) come only from env vars, never from committed files.
Size the Hikari pool for: heartbeat + claim + completion writes, not one connection per running job.

---

## 8. Reliability requirements

- **Graceful shutdown (SIGTERM):** a `SmartLifecycle` bean stops polling first, then waits up to `shutdown-grace-period` for running jobs, then releases leases of anything still running (set `locked_until = now()` where `locked_by = me`) so another pod resumes immediately. Kubernetes `terminationGracePeriodSeconds` must exceed this value.
- **Retries:** exponential backoff `min(initial * multiplier^retry_count, max)` plus ±10% jitter. Unit-test the function.
- **Timeouts:** enforced per run; record `TIMED_OUT`; cancel the executor thread (interrupt).
- **Poison jobs:** exceeding `max_retries` leads to the `DEAD_LETTER` rule in §3 and a metric increment.
- **Database outages:** the polling loop must catch and log exceptions, back off, and keep going. Never let the loop thread die.
- **Idempotent API operations:** pause on an already-paused job is a 200 no-op.

---

## 9. Security requirements
- API key auth (Phase 5), constant-time compare.
- SSRF guard on HTTP executor (§6).
- Run the container as non-root, read-only root filesystem where possible.
- No secrets in logs, images, or the repo. Provide `.env.example`, git-ignore `.env`.
- Validate and size-limit all payloads (e.g., max 64 KB JSON).

---

## 10. Observability
- Structured logs include `jobId`, `runId`, `nodeId` via MDC.
- Micrometer metrics (Prometheus at `/actuator/prometheus`):
  - `scheduler_jobs_due` (gauge: count of ACTIVE jobs with `next_run_at <= now()`; refresh every poll)
  - `scheduler_claim_duration` (timer), `scheduler_claimed_total` (counter)
  - `scheduler_run_duration` (timer, tags: `type`, `outcome`)
  - `scheduler_runs_total` (counter, tags: `type`, `outcome`)
  - `scheduler_running` (gauge: in-flight runs on this pod)
  - `scheduler_dead_letter_total` (counter)
  - `scheduler_lag_seconds` (gauge: now minus the oldest due `next_run_at`; the key autoscaling and alerting signal)
- Readiness probe must fail until Flyway has completed and the DB is reachable. Liveness must NOT depend on the DB (avoids restart storms during DB outages).

---

## 11. Containerization and Kubernetes

### Docker
- Multi-stage `Dockerfile`: build stage (Maven + JDK 21, dependency layer cached) then runtime stage (Temurin 21 JRE, non-root UID, `JAVA_OPTS` with `-XX:MaxRAMPercentage=75`).
- `.dockerignore` present. Image tag uses the git SHA.
- `docker-compose.yml`: `postgres:16` (with healthcheck) plus `app-api` (role API) and 2x `app-worker` (role WORKER, run with `deploy.replicas` or two named services) so scaling can be tested locally.

### Kubernetes (`k8s/` with Kustomize: `base/` and `overlays/{local,prod}`)
- `Deployment` for **api** and **worker** (same image, different `SCHEDULER_ROLE`), `Service` for api, optional `Ingress`.
- `ConfigMap` (non-secret config), `Secret` (DB creds, API key; the manifest uses placeholders only).
- Probes: startup, readiness (`/actuator/health/readiness`), liveness (`/actuator/health/liveness`).
- Resource requests and limits; `terminationGracePeriodSeconds: 90`; `PodDisruptionBudget` (`minAvailable: 1`) for workers.
- `HorizontalPodAutoscaler` (autoscaling/v2) on CPU for baseline (min 2, max 10).
- Stretch: autoscale workers on `scheduler_lag_seconds` / `scheduler_jobs_due` using **KEDA** (Prometheus or PostgreSQL scaler).
- Local: `kind` cluster plus a Postgres StatefulSet in the `local` overlay. Prod overlay points at managed Postgres via Secret.
- Flyway runs on app startup. It is safe with many pods because Flyway takes a DB lock. Mention the alternative (a migration Job) in the README.

### CI (`.github/workflows/ci.yml`)
Build, run tests (Testcontainers works on GitHub runners), build the Docker image, push to GHCR on `main`.

---

## 12. Testing strategy

Required tests, written alongside the code (not at the end):

1. **Unit:** cron next-time computation (incl. timezone and DST), backoff function, payload validation, SSRF guard.
2. **Repository/integration (Testcontainers Postgres):** claim query returns only due + ACTIVE + unlocked jobs, respects `LIMIT`, respects expired leases.
3. **Concurrency (the key test):** start 2-4 scheduler instances against one Postgres, insert 500 due jobs, assert every job ran **exactly once** (use a recording test executor).
4. **Failure/recovery:** simulate a worker dying mid-run (stop heartbeat, no completion), assert another worker takes over after lease expiry and the old run becomes `LOST`.
5. **Retries and dead-letter:** failing executor leads to N attempts with growing delays, then `DEAD_LETTER`.
6. **Graceful shutdown:** in-flight job finishes, or its lease is released.
7. **API (MockMvc or `@SpringBootTest`):** CRUD, validation errors, pause/resume/trigger, pagination.
8. **HTTP executor (WireMock):** success, non-expected status, timeout, SSRF block.

Use Awaitility for async assertions, never `Thread.sleep`.

---

## 13. Suggested package layout

```
com.example.jobscheduler
├── JobSchedulerApplication
├── api/            controllers, DTOs, exception handler
├── domain/         Job, JobRun, enums (status, schedule type, misfire policy)
├── repository/     JPA repositories + JobClaimRepository (native SQL)
├── service/        JobService (CRUD + state transitions), ScheduleCalculator, BackoffPolicy
├── engine/         SchedulerLoop, JobRunner, HeartbeatManager, Janitor, ShutdownCoordinator
├── executor/       JobExecutor, ExecutorRegistry, HttpJobExecutor, SsrfGuard
├── config/         properties records (@ConfigurationProperties), security filter, metrics
└── util/           NodeId, Clock abstraction
```
Inject a `java.time.Clock` bean wherever Java-side time is needed, so tests can control it. (Lease and claim decisions still use the DB clock.)

---

## 14. Phased delivery plan

Complete phases in order. After each phase: run `./mvnw verify`, make sure everything is green, update `docs/PROGRESS.md` (what was built, what's next), and write the learning note (see §15). Make small, meaningful git commits (Conventional Commits: `feat:`, `fix:`, `test:`, `chore:`, `docs:`).

### Day 1: Core engine

**Phase 0: Skeleton** (30 min)
- Spring Boot 3 / Java 21 / Maven project, package layout, `application.yml`, `.gitignore`, `.env.example`, `README.md` stub, `docker-compose.yml` with Postgres only.
- DoD: `./mvnw verify` passes; app starts against compose Postgres; `/actuator/health` is UP.

**Phase 1: Persistence**
- Flyway V1/V2 (§4), entities, repositories, Testcontainers base class.
- DoD: repository tests pass; `ddl-auto=validate` passes.

**Phase 2: API**
- DTOs, `JobService`, `ScheduleCalculator` (cron/one-time/fixed-rate), all endpoints in §5 except runs, exception handler, OpenAPI.
- DoD: API tests pass; manual curl flow works (create, list, pause, resume, trigger).

**Phase 3: Engine v1**
- `JobExecutor` plus `ExecutorRegistry`, `HttpJobExecutor` (without SSRF yet), `JobClaimRepository` (claim SQL), `SchedulerLoop`, `JobRunner` (writes `job_runs`, completes the job), run-history endpoints.
- DoD: a cron job hitting a WireMock endpoint runs on time; the concurrency test (2 instances, no double-run) passes.

### Day 2: Reliability and containers

**Phase 4: Reliability**
- Retries/backoff, timeouts, misfire policy, heartbeat plus lease-loss detection, Janitor (`LOST` runs, retention purge), dead-letter, graceful shutdown coordinator, resilient loop.
- DoD: tests 3-6 in §12 pass.

**Phase 5: Observability and security**
- Metrics (§10), MDC logging, readiness/liveness split, API key auth, SSRF guard, payload size limits, role-based enabling (`ALL|API|WORKER`).
- DoD: `/actuator/prometheus` shows all metrics; unauthorized calls get 401; SSRF test passes.

**Phase 6: Docker**
- Multi-stage Dockerfile, full `docker-compose.yml` (api + 2 workers + postgres).
- DoD: `docker compose up --scale app-worker=3` works; a script (`scripts/smoke.sh`) creates 100 jobs and verifies each ran once; killing a worker (`docker kill`) never loses jobs.

### Day 3: Kubernetes and cloud

**Phase 7: Kubernetes**
- Kustomize base and overlays, probes, resources, PDB, HPA, graceful termination.
- DoD: deploys to `kind`; `kubectl scale deploy/worker --replicas=5` and back to 1 works with no lost or double runs; `kubectl delete pod` mid-run recovers.

**Phase 8: CI/CD and docs**
- GitHub Actions workflow, README (architecture diagram, quickstart, API examples, K8s deploy guide for EKS/GKE/AKS with managed Postgres, delivery semantics, scaling guidance), `docs/HOW_IT_WORKS.md`.
- DoD: CI green; a new developer can run the project from the README alone.

**Stretch (only if time remains):** KEDA autoscaling on `scheduler_lag_seconds`, extra executors behind flags, Helm chart, per-job concurrency limits, job tags/labels, webhooks on failure.

---

## 15. Working agreement for the agent

1. **Plan first, then build.** At the start of each phase, state in 3-6 bullets what you'll do. Then do it.
2. **Run the build and tests yourself** (`./mvnw verify`, `docker compose ...`, `kind`...) and fix failures before declaring a phase done. Never claim something works without running it. If you can't run something (e.g., no Docker), say so plainly.
3. **Don't silently skip or stub** requirements. If you must defer something, record it in `docs/PROGRESS.md` under "Deferred".
4. **Teach as you go.** After each phase, append to `docs/HOW_IT_WORKS.md` a short section (5-10 lines, plain language): what the phase added, *why* it's designed that way, and the one concept to understand (e.g., "why SKIP LOCKED", "what a lease is", "why at-least-once").
5. **Keep changes small and reviewable.** One concern per commit.
6. **Ask before** adding dependencies outside §2, changing the DB schema design in §4, or changing delivery semantics.
7. **Code quality:** records for DTOs, constructor injection, no field injection, no `System.out`, meaningful names, Javadoc on the engine's non-obvious logic (the claim SQL, lease and heartbeat rules, completion CAS).
8. **Never** commit secrets, edit an applied Flyway migration, hold DB row locks while executing a job, or use the pod clock for lease logic.
9. At the end of each phase, reply with: what was built, test results, how to try it (exact commands), and what the next phase will do.

## 16. Commands cheat sheet

```bash
./mvnw clean verify                       # build + all tests
./mvnw spring-boot:run                    # run locally (needs Postgres)
docker compose up -d postgres             # DB only
docker compose up --build --scale app-worker=3
docker build -t job-scheduler:dev .
kind create cluster --name scheduler
kubectl apply -k k8s/overlays/local
kubectl scale deploy/scheduler-worker --replicas=5
```

## 17. Definition of Done (whole project)

- [ ] All phases complete; `./mvnw verify` green; CI green
- [ ] Exactly-once-per-slot demonstrated under 2-4 concurrent workers (automated test)
- [ ] Worker crash recovery demonstrated (automated test plus a Docker or K8s manual check)
- [ ] Image builds, runs as non-root, passes probes
- [ ] Deploys to Kubernetes and scales up and down with no lost or duplicate runs
- [ ] README and `docs/HOW_IT_WORKS.md` explain the architecture, the claim/lease/heartbeat mechanism, and delivery semantics
