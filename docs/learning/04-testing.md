# 4. Testing, mocks, and smoke tests — Q&A

## What is an automated test?

It sets up a situation, performs an action, and checks an expected outcome. A failure tells us
which expectation was not met. It does not automatically tell us whether production code,
test assumptions, timing, or environment caused the problem.

For example, [BackoffPolicyTest](../../src/test/java/com/example/jobscheduler/service/BackoffPolicyTest.java)
checks that an initial 10-second delay, multiplier 2, and retry index 2 produce 40 seconds when
jitter is zero. The same test also checks the maximum and ±10% jitter boundaries.

## How do unit, integration, and end-to-end tests differ?

| Kind | Scope | Example here |
|---|---|---|
| Unit | A small piece of behavior, usually without a running app or DB. | `BackoffPolicyTest`, `ScheduleCalculatorTest` |
| Repository integration | Java persistence and real PostgreSQL together. | `PersistenceTest` |
| API integration | HTTP routing, validation, auth, services, and persistence. | `JobApiTest`, `SecurityIntegrationTest` |
| Engine concurrency/recovery | Multiple loops and real database coordination. | `EngineIntegrationTest` |
| Deployment smoke | Built containers and their network/configuration work together. | `scripts/smoke.sh`, `scripts/k8s-smoke.sh` |

These categories can overlap. The useful question is which boundaries are real in a given test,
and which failures it could actually detect.

## What does each testing library do?

| Tool | Purpose here |
|---|---|
| JUnit 5 | Discovers and executes `@Test` methods and test lifecycle hooks. |
| AssertJ | Supplies readable assertions such as `assertThat(actual).isEqualTo(expected)`. |
| Spring Boot Test | Creates a Spring application context for integration tests. |
| MockMvc | Exercises Spring MVC in-process without a real network client/server exchange. |
| Testcontainers | Starts disposable real PostgreSQL containers for database tests. |
| WireMock | Runs a controllable HTTP server to simulate webhook responses and delays. |
| Awaitility | Rechecks an asynchronous condition until success or a bounded timeout. |

`@SpringBootTest` loads the application configuration; it does not inherently mean a real HTTP
port is opened. `@DataJpaTest` focuses on persistence. Our repository tests explicitly use
Testcontainers PostgreSQL rather than replacing PostgreSQL with an in-memory database.

## What is a mock? Is WireMock a mocked Java object?

A mock replaces a collaborator with controlled behavior, often recording how it was called.
A fake supplies a simpler working implementation, such as a recording executor. A stub gives
predefined answers. These words describe test roles and sometimes overlap.

WireMock is an actual HTTP test server. The real `HttpJobExecutor` makes network requests to
it, so we can verify headers, status handling, response limits, and timeouts without depending
on an external website. The production webhook target is the replaced boundary.

## Why use real PostgreSQL in tests?

Our correctness depends on PostgreSQL SQL, JSONB, transactions, and `SKIP LOCKED` behavior.
A different database could pass a similar-looking test while behaving differently in production.
[PostgresIntegrationTest](../../src/test/java/com/example/jobscheduler/support/PostgresIntegrationTest.java)
starts PostgreSQL and supplies its dynamic connection settings to Spring.

Docker must be running and accessible for these tests. A socket permission error is an
environment problem; it is not evidence that the scheduler's claim logic failed.

## How do you test something that happens later?

Use a bounded condition, for example “all 500 jobs have completed within the deadline.”
Awaitility polls that condition. A fixed sleep guesses how fast the machine will be and can
make tests slow or flaky. Latches let tests coordinate events such as “execution started”
without guessing a delay.

[EngineIntegrationTest](../../src/test/java/com/example/jobscheduler/engine/EngineIntegrationTest.java)
uses independent scheduler instances against one PostgreSQL database. It verifies 500 due jobs
execute once in the normal concurrent scenario, and separately tests lease expiry, stale
completion, retries, timeout, and graceful shutdown.

## Why can a test pass locally but fail in CI?

Concurrent threads can interleave differently on different machines. An assertion can be wrong
if it assumes another thread is idle. For example, the polling loop briefly reserves a slot
before asking PostgreSQL for jobs. Even an empty claim can temporarily make `inFlight()` equal
one. Checking for zero during live polling is a race, not proof of a leaked execution slot.

`PollingResilienceTest` checks that polling recovers after simulated database errors, then stops
the loop before checking that all slots were returned. It also verifies no job executed for
empty claims. This checks a stable outcome rather than guessing when a background poll is idle.
Increasing a timeout or rerunning CI alone would not remove that invalid timing assumption.

## What is a smoke test?

A smoke test is a broad check that a deployed system's main path works. It catches problems
such as an image that cannot start, a bad database hostname, missing credentials, or containers
that cannot reach each other. It complements detailed behavior tests.

Our Docker smoke test is deliberately stronger than a simple health request: it creates 100
jobs, kills the worker handling a held request, and checks recovery and target effects. The
Kubernetes smoke test scales workers 1 → 5 → 1 and deletes a pod during work.

## What is the difference between health checking and smoke testing?

A health endpoint answers a narrow question about one running instance. Readiness asks whether
it is ready to serve; liveness asks whether it is alive enough to keep running. A smoke test
performs a real workflow across components and checks the result.

## Does “100 successful jobs” prove no request was repeated?

No. The recorded Docker crash check had 100 successful final runs, one LOST run, and 101 HTTP
attempts. The test receiver applied 100 logical effects by deduplicating the one-time jobs.
A crash can happen after a target acts but before the worker records success.

Normal-concurrency “each ran once” tests prove that tested scenario, not global exactly-once
delivery. See [verification evidence](../VERIFICATION.md). A recurring job needs a stable
business-operation identifier for deduplication; its job ID alone would also suppress future
legitimate scheduled executions.

## How do I run tests?

From the project root, use Java 21 and a working Docker daemon. Maven Wrapper downloads the
pinned Maven version; you do not need a separate system Maven installation.

```bash
./mvnw verify
./mvnw -Dtest=BackoffPolicyTest test
./mvnw -Dtest=EngineIntegrationTest test
```

`verify` runs the build through Maven's verification phase, including the tests configured in
this project. Test reports are in `target/surefire-reports`. `-DskipTests` skips execution and
therefore cannot provide equivalent evidence.

Deployment smoke tests require Docker/Compose or Docker/kind/kubectl, plus Python 3. They build
images and create temporary infrastructure, so expect more time and resource use:

```bash
./scripts/smoke.sh
./scripts/k8s-smoke.sh
```

The helpers clean up their test resources by default. The Kubernetes helper uses a separate
cluster by default and refuses to replace an existing scheduler namespace.

**Check your understanding:** If a unit test passes but a container cannot connect to PostgreSQL,
which test layer is likely to expose the missing network/configuration boundary? A deployment smoke test.
