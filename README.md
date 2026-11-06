# Distributed Job Scheduler

A learning project using Java 21, Spring Boot 3, and PostgreSQL 16.
PostgreSQL will coordinate independent workers using short transactions and leases.
See [AGENTS.md](AGENTS.md) for the design and [progress](docs/PROGRESS.md) for delivery status.

## Local setup

Requires JDK 21, Docker with Compose, and permission to access the Docker daemon.
The Maven Wrapper downloads Maven on first use; no system Maven is needed.

```bash
cp .env.example .env
# Edit .env and set DB_PASSWORD to a local development password.
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
The default Compose database port is 5432; it must be available before starting PostgreSQL.

The REST API and job engine are delivered in later phases.
