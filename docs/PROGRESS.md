# Progress

## Phase 0 — Skeleton

- Added Java 21 / Spring Boot 3.5 project, Maven Wrapper, UTC Clock, and base packages.
- Added environment-driven configuration, PostgreSQL 16 Compose service, and quickstart.
- Verification: `./mvnw verify` passed on Java 21 (1 test, 0 failures).
- Startup/health verified against Compose PostgreSQL 16: `/actuator/health` returned `UP`.
- Phase 1 persistence verification is complete below.

## Phase 1 — Persistence

- Added V1/V2 migrations exactly matching the specified tables and indexes.
- Added Job/JobRun mappings, string enums, JSONB payloads, database-generated JPA audit timestamps,
  and optimistic locking for jobs. Job types stay strings for future executor plugins.
- Added job/run repositories, specification support, and paginated newest-first run lookup.
- Added PostgreSQL 16 Testcontainers base and eight repository tests covering migrations/schema,
  schedule/JSON round trips, defaults, duplicate names, optimistic locking, pagination,
  foreign keys, and cascading deletion.
- Verification: `./mvnw verify` passed on Java 21: 9 tests, 0 failures/errors/skips.
  All eight PostgreSQL 16 repository tests ran, including Hibernate schema validation.
- Packaged application started against Compose PostgreSQL; Flyway applied V1/V2.
  Health, readiness, and liveness endpoints each returned `UP`.
- Docker access worked using `sg docker -c '...'` to apply newly granted group membership.
  Verification used temporary Compose port 15432 and application port 18080 because host
  port 5432 was occupied. No existing database was stopped; temporary resources were removed.
- Phase 2 API is complete below.

## Phase 2 — API

- Added record DTOs and all job endpoints: create/get/list/update/delete/pause/resume/trigger.
- Added paginated, stable sorting and combined status/type/case-insensitive literal-name filters.
- PUT replaces a definition, requires its current version, and recalculates nextRunAt only
  for schedule changes. Duplicate names and stale edits return 409.
- Added schedule calculations for six-field cron, fixed rate, and one-time schedules;
  timezone validation and tests cover spring DST gaps and fall DST overlaps.
- Added HTTP payload configuration validation (including a 64 KB UTF-8 JSON limit).
  Execution/registry integration remains in Phase 3; SSRF enforcement remains in Phase 5.
- Added ProblemDetail errors with validation fields, and springdoc OpenAPI/Swagger UI.
- Defined state actions: repeated pause is a no-op; resume activates/recalculates;
  trigger activates and sets nextRunAt from PostgreSQL now(). Resume/trigger reset retries.
- Verification: `./mvnw verify` passed: 45 tests, 0 failures/errors/skips
  (12 API, 8 scheduling, 16 payload-validation, 8 persistence, 1 clock).
- Packaged application health returned UP against isolated Compose PostgreSQL 16.
  Manual curl flow passed: create 201; list/pause/resume/trigger/get/update 200;
  stale update 409; OpenAPI 200; delete 204; subsequent get 404.
  Temporary verification ports: database 15433, HTTP 18082; resources cleaned up afterward.
- Added runnable curl examples and documented schedule/state/version behavior in README.
- Phase 3 engine and run history are complete below.

## Local setup fix — Database port collision

- Made the Compose host port configurable with DB_PORT (default 5432).
- Documented using port 15432 and a matching DB_URL when another project owns 5432.
- Updated the ignored local .env without changing credentials.
- Verified Compose PostgreSQL health, Flyway V1/V2 startup, application health UP,
  and HTTP 200 from the jobs API against the scheduler database on port 15432.
- Left scheduler PostgreSQL running; stopped the temporary verification app.

## Phase 3 — Engine v1

- Added JobExecutor, execution records, and a Spring-discovered ExecutorRegistry;
  the API delegates payload validation to the registered executor.
- Added Java HttpClient execution with execution headers, methods/headers/body/status rules,
  deadline enforcement through response consumption, bounded response retention, and no redirects.
  WireMock standalone and Awaitility are test-only dependencies from the prescribed stack.
- Added atomic UPDATE / FOR UPDATE SKIP LOCKED / RETURNING claims and immutable job snapshots.
  Claim transactions end before execution; leases and ownership checks use PostgreSQL now().
- Native claim/completion updates increment version so stale JPA CRUD writes fail.
  Job uses dynamic updates to prevent a pause from restoring an older heartbeat lease.
- Added a SmartLifecycle polling loop with ±20% jitter and capacity reserved by semaphore
  before each claim. Each process has an independent loop, executor pool, and unique node ID.
- Added RUNNING history insertion and transactional outcome/job-state completion guarded by
  owner and unexpired lease. Pause survives completion. Other live-lease mutations return 409.
- Wired heartbeat renewal, deadline cancellation, and exponential retry transitions into the
  core path. Exhausted one-time failures become DEAD_LETTER; recurring failures reset retries
  and advance from completion time. Baseline shutdown stops polling and waits before release.
- Added paginated job history and individual run endpoints with record responses.
- Final verification: `./mvnw verify` passed on Java 21: 62 tests, 0 failures/errors/skips.
  Two independent loops processed 500 jobs: 500 successful run records, one execution per job,
  both node IDs represented, all leases released. Also tested SKIP LOCKED under an open row lock,
  claim filtering/limits/expired leases, capacity bounds, stale completion/heartbeat fencing,
  heartbeat renewal beyond lease duration, JPA/heartbeat coexistence, retry/exhaustion,
  timeout recording, HTTP success/error/deadline/truncation/no-redirect, and run-history API.
- Cron-to-WireMock test passed with execution within 2 seconds of its scheduled slot.
- Packaged application smoke check passed against isolated Compose PostgreSQL: automatic
  polling produced one HTTP 200, one SUCCEEDED run, and one COMPLETED job; health/history/detail
  endpoints returned 200. Temporary resources were cleaned up; existing databases were untouched.
- Added README commands and delivery/overlap explanations; HOW_IT_WORKS includes a sequence diagram.
- Next: Phase 4 reliability, including crash recovery, janitor, misfire handling,
  database-outage backoff, and expanded retry/shutdown scenarios. Stopped before Phase 4.

## Deferred

- Phase 4, as planned: abandoned RUNNING rows need a janitor to become LOST after a process
  crash/lease loss; old-run retention purge and misfire SKIP handling are not implemented yet.
- Phase 4: polling currently catches failures and retries with normal poll jitter; explicit
  database-outage backoff and full crash/retry/shutdown test scenarios remain to be delivered.
- Phase 5, as planned: SSRF guard, API key auth, scheduler metrics/MDC, role-specific enabling,
  and the readiness/liveness split. Engine polling can currently be disabled via scheduler.enabled.
- No phase-3 schema changes or edits to applied migrations.
