# Progress

## Phase 0 — Skeleton

- Added Java 21 / Spring Boot 3.5 project, Maven Wrapper, UTC Clock, and base packages.
- Added environment-driven configuration, PostgreSQL 16 Compose service, and quickstart.
- Verification: `./mvnw verify` passed on Java 21 (1 test, 0 failures).
- Startup/health against Compose PostgreSQL is unverified: Docker socket permission denied.
- Next: Phase 1 persistence, migrations, and PostgreSQL repository tests.

## Phase 1 — Persistence implementation; database verification pending

- Added V1/V2 migrations exactly matching the specified tables and indexes.
- Added Job/JobRun mappings, string enums, JSONB payloads, database-generated JPA audit timestamps,
  and optimistic locking for jobs. Job types stay strings for future executor plugins.
- Added job/run repositories, specification support, and paginated newest-first run lookup.
- Added PostgreSQL 16 Testcontainers base and eight repository tests covering migrations/schema,
  schedule/JSON round trips, defaults, duplicate names, optimistic locking, pagination,
  foreign keys, and cascading deletion.
- Verification: Java 21 production/test compilation passed; UTC unit test passed.
  `./mvnw verify` failed during Testcontainers startup because Docker socket access was denied.
  Repository assertions and Hibernate schema validation have not run; no tests were disabled.
- Next: rerun database verification when Docker is accessible, then Phase 2 API (not started).

## Deferred

- Docker-backed startup and health verification pending Docker socket access.
