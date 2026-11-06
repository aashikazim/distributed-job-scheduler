# 3. Database access and worker coordination — Q&A

## How does Java connect to PostgreSQL?

```text
DB_URL / DB_USER / DB_PASSWORD
               |
        Boot datasource setup
               |
        Hikari connection pool
               |
    PostgreSQL JDBC driver -- TCP --> PostgreSQL
```

The JDBC driver speaks PostgreSQL's protocol. Hikari keeps a bounded set of reusable
connections, avoiding a fresh network connection for each query. Boot configures this from
[application.yml](../../src/main/resources/application.yml) and the runtime environment.

A connection is a database conversation. It is not a Java worker thread, job, or pod.
An application can have many waiting HTTP executions while using only a few database connections
for short claims, heartbeats, and completion writes.

## What are JPA, Hibernate, Spring Data JPA, and JDBC?

| Tool | Responsibility here |
|---|---|
| JDBC | Java's lower-level database API: execute SQL with parameters and read results. |
| JPA | A specification for mapping Java objects to relational data. |
| Hibernate | The JPA implementation that maps our entities and generates CRUD SQL. |
| Spring Data JPA | Supplies repository implementations and query/pagination support. |
| Spring `JdbcClient` | Makes explicit parameterized JDBC SQL easier to write and map. |

[JobRepository](../../src/main/java/com/example/jobscheduler/repository/JobRepository.java)
extends Spring Data interfaces. We do not write its basic save/find implementation ourselves.
The engine uses explicit SQL in
[JobClaimRepository](../../src/main/java/com/example/jobscheduler/repository/JobClaimRepository.java)
and [JobExecutionRepository](../../src/main/java/com/example/jobscheduler/repository/JobExecutionRepository.java)
because ownership, expiry, and atomic updates need precise database behavior.
Both approaches reach the same PostgreSQL tables through the datasource.

## Who creates the tables? Does Hibernate create them?

Flyway applies versioned SQL from [db/migration](../../src/main/resources/db/migration).
V1 creates `jobs`; V2 creates `job_runs`. Flyway tracks applied versions and checksums in its
schema history table. Add a new migration to change an existing database; do not rewrite an
already applied one.

Hibernate uses `ddl-auto: validate`: it checks the mappings against the schema instead of owning
schema changes. If several pods start together, Flyway coordinates migration through a database
lock. A separate migration Job is an alternative deployment design, not implemented here.

## Why two tables?

`jobs` stores the definition and current scheduling state: payload, next due time, retries,
status, and lease. `job_runs` stores execution history: each attempt's run ID, owner, start/end,
and outcome. One recurring job can therefore have many run records.

`JobStatus.ACTIVE` describes scheduling eligibility. `RunStatus.RUNNING` describes a particular
execution. A completed one-time job has job status COMPLETED and normally a SUCCEEDED final run.

## What does a transaction protect?

A transaction groups database changes so that they commit together or roll back together.
For example, recording a finished run and updating the job's next schedule must agree.
A transaction cannot undo an HTTP request that an external target has already processed.

JPA `saveAndFlush` sends pending SQL before the transaction ends; flushing is not itself a
commit. The surrounding service transaction still determines whether changes persist.
`readOnly=true` communicates intent and enables optimizations; it is not a complete authorization
mechanism for preventing all possible writes.

## How do two workers avoid selecting the same due job?

They use `FOR UPDATE SKIP LOCKED` inside the atomic claim statement. Think of two people picking
unclaimed tickets from one shared tray: if one person currently holds a ticket, the other picks
another rather than waiting for it.

```text
Worker A                    PostgreSQL                    Worker B
claim transaction ----> locks job 1 briefly <---- claim transaction
                        B skips job 1; takes job 2
commit lease A/expiry                                    commit lease B/expiry
execute HTTP job 1                                       execute HTTP job 2
```

The statement records an owner and lease expiry before committing. The short row lock protects
the claim transaction. The stored lease protects ownership after that transaction has ended.
We do not leave a database transaction open while waiting on a webhook.

## What if a job runs longer than the lease?

A heartbeat extends the lease while ownership is still valid. The default lease is 60 seconds,
with heartbeat every 20 seconds. If the worker dies, heartbeats stop and the lease expires.
Another worker can then claim the job. Janitor/claim cleanup marks the abandoned run LOST.

Completion checks owner, valid lease, and active run identity so stale work cannot overwrite a
new owner's database state. This is called fencing. It cannot recall an HTTP request already
received by another system; targets still need idempotency for crash-related repeats.

## Why use the database clock?

Two pods can disagree about local time. Claim and lease checks use PostgreSQL `now()`, so they
share one authority. Java-side schedule calculations use an injected `Clock`, making timezone,
DST, and deadline behavior testable. Stored timestamps use `timestamptz` and Java `Instant`.

## What is optimistic locking?

The `@Version` field detects an update based on an old version of a job. If two users edit the
same version, the later conflicting update gets 409 instead of silently overwriting changes.
This addresses editing conflicts. The engine's lease and ownership SQL address execution
coordination. They solve different problems.

## Inspect the database

With Compose PostgreSQL running, this is a read-only query; the database user/name are resolved
inside its container:

```bash
docker compose exec postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT name, status, next_run_at, locked_by FROM jobs ORDER BY created_at DESC LIMIT 5;"'
```

Expected observation: stored scheduling state, or zero rows if no jobs exist. A NULL lock is
normal for an idle job. Never manually edit leases to troubleshoot a production run.

**Check your understanding:** Does a running webhook need to hold a JDBC connection for its
whole duration? No. Database operations are short; the webhook runs between them.
