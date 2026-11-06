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
- Phase 4 reliability is complete below.

## Phase 4 — Reliability

- Added the agreed configurable 60-second misfire threshold. SKIP advances stale recurring
  jobs and completes stale one-time jobs without execution; explicit retries remain eligible.
  FIRE_ONCE_NOW still executes once and computes the next slot from completion time.
- Added a janitor in the poll loop (default every 30 seconds) that marks expired/replaced-owner
  RUNNING attempts LOST and purges terminal history older than run-retention-days (default 30),
  in batches of at most 1,000 rows. Locks/ownership decisions use the database clock.
- Claims fence abandoned runs in the claim transaction. Claim-version checks reject delayed
  old starts; active run-ID checks fence old heartbeat/completion writes, including same-node
  takeovers. Row locks coordinate claim/start/heartbeat/completion/janitor without covering execution.
- Hardened runner cleanup, timeout interruption, and lease-loss cancellation. Failed attempts
  retry with exponential capped backoff and jitter. Successful retries reset the count;
  one-time exhaustion keeps its retry count and dead-letters; recurring exhaustion advances
  the next slot and resets retries. Dead-letter metrics increment only after commit.
- Hardened idempotent SmartLifecycle shutdown: stop polling, wait for in-flight work, then
  cancel unfinished work and clear/expire owned leases. Owner clearing fences late heartbeats.
  If DB release fails, shutdown tolerates the outage and leases expire naturally.
- Added polling outage backoff/reset and bounded connection/query/socket waits. Spring's
  shutdown-phase budget is 75s, above the default worker grace period of 60s.
- Final `./mvnw verify` passed on Java 21: 76 tests, 0 failures/errors/skips.
  Section 12 tests 3–6 all passed:
  - Concurrency: two independent loops processed 500 jobs exactly once during normal operation.
  - Recovery: stopped execution/heartbeats without completion/release; another worker took
    over after natural lease expiry; old run LOST, replacement SUCCEEDED.
  - Retries: maxRetries=2 produced attempts 1/2/3 with jittered ~1s/~2s delays, then DEAD_LETTER.
    Eventual success reset retries; exhausted recurring failure advanced its next schedule slot.
  - Shutdown: enough grace allowed in-flight success and stopped new claims; insufficient
    grace interrupted execution and released the lease for immediate replacement execution.
- Additional tests passed: timeout interruption/TIMED_OUT, heartbeat lease-loss cancellation,
  same-node stale claim/run fencing, live/replaced/expired janitor cases, retention batch bounds,
  misfire SKIP/FIRE_ONCE_NOW/retry behavior, and injected DB polling/release failures.
- Real packaged-process recovery check passed against isolated Compose PostgreSQL 16:
  SIGKILL during HTTP execution; a second application recovered the job. Two unique HTTP
  attempts, one LOST and one SUCCEEDED history row, job COMPLETED. This confirms at-least-once
  behavior; it does not imply exactly-once external effects. Verification resources were cleaned up.
- Updated README failure-scenario table and HOW_IT_WORKS learning notes.
- Phase 5 observability/security is complete below.

## Phase 5 — Observability and security

- Added all section 10 metrics: cached due/lag gauges using one PostgreSQL-clock snapshot,
  claim duration/count, local running gauge, run duration/count tagged by bounded executor
  type/outcome, and the existing after-commit dead-letter counter. Workers refresh backlog
  every poll; API pods refresh it with a housekeeping task, without claiming jobs.
  Prometheus scrapes never query the DB; cached gauges retain their last successful values.
- Added structured Logstash JSON and scoped jobId/runId/nodeId MDC propagation into virtual
  execution threads, polling, and heartbeats. Request/response/execution diagnostics redact
  payloads and result bodies; checked that temporary credentials were absent from app logs.
- Readiness includes traffic state and DB health; liveness includes only process state.
  Flyway must finish before startup completes. Tests cover DB-down and refusing-traffic states.
- Added mandatory API key configuration for API/ALL, fixed-size digest/constant-time comparison,
  401 ProblemDetail responses for missing/wrong/duplicate keys, bounded request reads, and
  rejection of ambiguous path aliases before public actuator/docs exemptions.
  Health/info/Prometheus remain open. Swagger describes X-API-Key authorization.
- Added property-based ALL/API/WORKER conditions: API has no engine; WORKER has no job/history
  controllers and returns 404 for all non-actuator routes, including docs. WORKER needs no API key.
- Added SSRF checks on all resolved A/AAAA addresses at validation and execution. Denies private,
  loopback, link-local/metadata, IPv6 local/transition, multicast, and unspecified destinations;
  rejects mixed public/private answers and DNS failures. Exact-host overrides support local tests.
  HTTP redirects and system proxies are disabled. Java HttpClient does its own connection lookup,
  so socket-level DNS pinning is outside this guard; rebinding-resistant deployments need egress controls.
- Added global 64 KB serialized payload validation, 128 KB raw POST/PUT request bounds, and
  JSON nesting depth 100. No additional dependencies or schema changes were needed.
- Final `./mvnw verify` passed on Java 21: 111 tests, 0 failures/errors/skips. This includes
  authentication/path aliases, API/WORKER roles, default SSRF block versus explicit allowance,
  IPv4/IPv6/DNS cases, payload bounds, health split, metrics, diagnostic redaction, virtual-thread
  MDC propagation/cleanup, and all prior 500-job concurrency/recovery/retry/shutdown tests.
- Packaged ALL-role app verification passed against isolated Compose PostgreSQL 16:
  missing key 401, valid key 200, metadata target 422. Captured real Prometheus output with
  three claims, one successful HTTP attempt, one failed/dead-lettered attempt, one active run,
  one due job, and ~15.45 seconds of lag. Saved docs/PROMETHEUS_SAMPLE.txt.
- Stopped only the isolated database: readiness 503, liveness 200. After restart readiness
  returned 200 and the polling loop recovered/completed the remaining job. Verification
  app/target/database resources were cleaned up; existing services were untouched.
- Generated a random key only in the ignored local .env because its API key was empty;
  did not print it or change an existing configured key. Updated README curl examples to
  load the environment/send the header and require explicit local-host allowance for the demo.
- Phase 6 Docker verification is complete below.

## Phase 6 — Docker

- Added multi-stage Maven/JDK 21 → Temurin 21 JRE image, dependency cache, pinned-wrapper
  checksum support via unzip, non-root UID/GID 10001, exec entrypoint, JAVA_OPTS heap sizing,
  source revision label, image healthcheck, and .dockerignore excluding secrets/build outputs.
- Expanded Compose to PostgreSQL 16, API, and two scalable workers by default. App containers
  have read-only root filesystems, /tmp tmpfs, dropped capabilities, no privilege escalation,
  memory/CPU bounds, health dependency, and a 90-second stop grace period.
- Added scripts/smoke.sh plus a standard-library Python driver and JDK-only optional test receiver.
  The script creates an isolated project/credentials, starts three workers, creates 100 jobs,
  kills the worker in the first HTTP run, and cleans up only its own stack/volume.
- Verification: `./mvnw verify` passed, 111 tests, 0 failures/errors/skips. Docker images built
  successfully using the current git SHA tag. Runtime checks passed: UID 10001, read-only
  filesystem, healthy readiness. `docker compose ... --scale app-worker=3` ran successfully.
- Crash smoke result: 100 COMPLETED jobs; 100 SUCCEEDED records; 1 LOST record; 101 HTTP
  attempts; 100 logical receiver effects. The other 99 jobs had exactly one HTTP attempt.
  The killed worker's request was retried after lease expiry. Receiver deduplication by stable
  one-time job ID prevented a duplicate effect; exactly-once scheduler delivery is not claimed.
- Documented Docker commands and appended learning notes. No runtime dependency/schema changes.
- Phase 6 committed as 334c739 before starting Phase 7.

## Phase 7 — Kubernetes

- Added Kustomize base plus local/prod overlays: separate API/worker Deployments, API Service,
  nonsecret ConfigMap, excluded placeholder-only Secret template, startup/readiness/liveness probes,
  resource bounds, non-root/read-only security contexts, tmpfs, disabled service-account mounting,
  90-second termination, worker PDB minAvailable=1, and CPU HPA min=2/max=10.
- Local adds PostgreSQL 16 StatefulSet/PVC and removes HPA for manual scaling. Prod references
  managed Postgres via Secret and mounts a provider CA ConfigMap for verified database TLS.
- Added SHA-based manifest renderer and isolated kind scaling/delete smoke scripts/overlay.
  Test receiver slow/hold endpoints let the test observe work during scaling and termination.
- Installed checksum-verified kind 0.33.0 and kubectl 1.37.2 under /tmp; created kind cluster
  scheduler (Kubernetes 1.37.0), using /tmp/scheduler-kind.kubeconfig without changing user kubeconfig.
- Fixed an overlay namespace composition issue found on the first rollout; cleaned up only the
  failed fresh deployment. Base, local, smoke, and prod render correctly; base/prod server dry-run passed.
- `./mvnw verify` passed: 111 tests, 0 failures/errors/skips. SHA-tagged images built/loaded.
- Actual kind smoke passed: scaled ready workers 1 → 5 → 1, deleted the owning pod during a live run,
  and allowed graceful completion. 100 COMPLETED jobs, 100 SUCCEEDED runs, 0 LOST runs,
  100 HTTP attempts, 100 receiver effects. Six node identities executed work across the exercise.
- Final API/worker/receiver/Postgres pods Ready; PostgreSQL 1Gi PVC Bound; worker PDB present;
  worker UID 10001. Cluster retained for review; port-forwards stopped. Its temporary credentials
  remain in a mode-600 file and are not committed. Existing Docker services were untouched.
- Phase 7 committed as e76759b before starting Phase 8.

## Phase 8 — CI/CD and final documentation

- Added SHA-pinned GitHub Actions: Java 21/Testcontainers verify, script checks, checksum-verified
  kubectl and all Kustomize rendering, test-report artifacts, then Docker build/100-job crash smoke.
  Successful main pushes additionally publish full-SHA amd64/arm64 images to GHCR.
- Builder uses BUILDPLATFORM so Java compilation runs natively for multi-platform runtime images.
- Reworked README into a complete quickstart/API/architecture/reliability/security/scaling guide;
  added managed-Postgres deployment instructions for EKS/RDS, GKE/Cloud SQL, and AKS/Azure PostgreSQL,
  including private routing, verified TLS/CA mounts, credential creation, and image authorization.
- Finished HOW_IT_WORKS, including the actual class/SQL path for one job and operational tradeoffs.
- Added create-env.sh, which generates mode-600 local credentials and refuses to overwrite an
  existing file. Verified fresh setup and preservation of existing credentials in a temporary copy.
- Local `./mvnw verify`: 111 tests passed, zero failures/errors/skips. README/cloud Bash examples,
  helper script syntax, and all Kustomize rendering passed. Running K8s worker pool limit confirmed
  through Prometheus at 5 connections.
- Remote GitHub Actions CI passed on implementation commit 5b88065, verification branch
  codex/finish-phases-6-8: verify and docker-smoke both successful. Main-only publish was
  intentionally skipped. Run: https://github.com/aashikazim/distributed-job-scheduler/actions/runs/42177732423
- Definition of Done audited in docs/DEFINITION_OF_DONE.md: all six implementation/local/CI
  items verified, with crash semantics and production-environment limits called out explicitly.
- Phases 6 (334c739), 7 (e76759b), and 8 (5b88065 plus this final audit) were committed separately
  in order. No further phases or stretch executors were started.

## Beginner learning guides

- Added seven Q&A chapters plus a reading index in `docs/learning`, linked from README and HOW_IT_WORKS.
- Covers Spring Boot startup/beans, request flow/annotations, PostgreSQL access and coordination,
  testing/smoke checks, Docker, Kubernetes orchestration, scaling, and crash recovery.
- Includes source links, diagrams, practical exercises, and the limits of at-least-once delivery.
- Documentation checks: 56 relative links resolve, 18 Bash blocks parse, and Markdown code fences
  and whitespace checks pass. Application tests were not rerun for this documentation-only change.

## CI fix — Polling resilience assertion race

- GitHub Actions exposed a race in `PollingResilienceTest`: an empty poll reserves capacity
  before claiming, so checking `inFlight() == 0` while polling is active is invalid.
- Moved the zero-capacity-leak assertion after loop shutdown, retained the recovery checks,
  and verified the runner never executes when claims are empty. Production code is unchanged.
- Added a Q&A explaining the timing issue in the testing learning guide.
- Verification: `./mvnw verify` with Java 21 and Docker-accessible PostgreSQL Testcontainers
  passed all 111 tests, with zero failures/errors/skips. The initial sandbox-only focused run
  could not initialize Mockito self-attachment; the full verification ran outside that restriction.

## Deferred

- Main-only GHCR publication/pull has not run; it requires a successful main push. CI is green
  on the verification branch, and all implementation changes are committed locally.
- Cloud guides were researched against provider documentation but not run in paid cloud accounts.
- CPU HPA was schema-validated but not exercised with metrics-server/load; local manual scaling passed.
- DNS/socket pinning is not provided by Java HttpClient; application DNS checks complement egress controls.
- Hard crashes may repeat attempts, as required by at-least-once delivery; target idempotency is separate.
