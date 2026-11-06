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
- Next: Phase 2 API (not started).

## Deferred

- None for Phases 0–1.
