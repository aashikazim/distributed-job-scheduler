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
The default Compose database port is 5432. If another project uses it, set
`DB_PORT=15432` and `DB_URL=jdbc:postgresql://localhost:15432/scheduler` in `.env`,
then export `.env` again and run `docker compose up -d --wait postgres`.
A password-authentication error can mean the app reached the other project's database.
Changing the port keeps the projects separate.

## Try the API (Phase 2)

After starting the application as above, run this in another Bash terminal.
Python 3 is used only to read the returned job ID; you can also copy the ID manually.

```bash
BASE=http://localhost:8080/api/v1
JOB_ID=$(curl -fsS -X POST "$BASE/jobs" \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-webhook","jobType":"HTTP","scheduleType":"FIXED_RATE","fixedRateSeconds":60,"payload":{"url":"https://example.com/hook","method":"POST","body":"{}","expectedStatus":[200,202]}}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -fsS "$BASE/jobs?page=0&size=10&type=HTTP&name=demo"
curl -fsS "$BASE/jobs/$JOB_ID"
curl -fsS -X POST "$BASE/jobs/$JOB_ID/pause"
curl -fsS "$BASE/jobs?status=PAUSED"
curl -fsS -X POST "$BASE/jobs/$JOB_ID/resume"
curl -fsS -X POST "$BASE/jobs/$JOB_ID/trigger"
```

POST returns 201 with a Location header. GET, PUT, and state actions return 200.
DELETE returns 204. The response includes the job's version and nextRunAt.
Lists return content, page, size, totalElements, and totalPages. Page starts at 0;
size is 1–100 (default 20). Filters combine; name is a case-insensitive literal substring.

PUT replaces the definition and requires the latest version. For example:

```bash
VERSION=$(curl -fsS "$BASE/jobs/$JOB_ID" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')
curl -fsS -X PUT "$BASE/jobs/$JOB_ID" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"demo-webhook\",\"jobType\":\"HTTP\",\"scheduleType\":\"FIXED_RATE\",\"fixedRateSeconds\":120,\"payload\":{\"url\":\"https://example.com/hook\",\"method\":\"POST\"},\"version\":$VERSION}"
curl -fsS -X DELETE "$BASE/jobs/$JOB_ID"
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
HTTP execution and its registry arrive in Phase 3. SSRF protection and API key auth arrive
in Phase 5. This phase stores job definitions and marks jobs due; it does not send webhooks.

OpenAPI JSON: `/v3/api-docs`. Interactive API documentation: `/swagger-ui.html`.
Run-history endpoints arrive with the engine in Phase 3.
