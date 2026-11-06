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
- Next: Phase 3 engine, executor registry, HTTP execution, atomic claims, and run-history endpoints.
  Phase 3 has not started.

## Deferred

- None for Phases 0–2. Engine execution/run history, reliability, and security follow
  their planned later phases.
