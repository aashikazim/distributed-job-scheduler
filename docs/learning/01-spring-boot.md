# 1. Spring Boot basics — Q&A

## What is Spring, and what does Spring Boot add?

Spring helps assemble Java objects and supplies facilities such as HTTP routing and database
transactions. Spring Boot uses your dependencies and configuration to choose useful defaults,
configure Spring, and start an application with an embedded web server.

Java is the language. The JVM runs it. Spring supplies application infrastructure. Boot sets up
that infrastructure. Maven downloads dependencies, compiles the code, and runs tests.

## Where does execution begin?

Open [JobSchedulerApplication.java](../../src/main/java/com/example/jobscheduler/JobSchedulerApplication.java).
Its `main` method calls `SpringApplication.run(...)`. Broadly, startup looks like this:

1. Read configuration, including environment variables.
2. Discover bean definitions and evaluate conditions such as the application role.
3. Create the necessary objects and connect their dependencies.
4. Configure the database connection pool; Flyway applies migrations before Hibernate validates the schema.
5. Start the web server and enabled lifecycle components, including the worker loop.
6. Finish startup and become ready when the readiness checks pass.

The exact initialization order is managed by Spring's dependencies and lifecycle, rather than
by a list of constructor calls in `main`. Startup errors can prevent the app from reaching readiness.

## What is a bean? Is it different from a normal Java object?

It is still a Java object. The difference is that Spring manages its creation, dependencies,
and lifecycle. For example, `JobService` is discovered through `@Service`.
[TimeConfiguration](../../src/main/java/com/example/jobscheduler/config/TimeConfiguration.java)
explicitly supplies a `Clock` bean:

```java
@Bean
public Clock clock() {
    return Clock.systemUTC();
}
```

When another managed object needs a `Clock`, Spring can supply that object. Most beans in this
application are singleton scoped: one instance per Spring application context, shared across
requests. Each separate application process has its own context and bean instances.

## What is dependency injection? Why use a constructor?

[JobService](../../src/main/java/com/example/jobscheduler/service/JobService.java) needs a repository,
a schedule calculator, an executor registry, a clock, database access, and a payload validator.
Its constructor declares those needs. Spring supplies the matching beans.

This is dependency injection: the caller supplies collaborators instead of the class secretly
creating them. Required dependencies are visible, fields can be final, and a test can supply a
controlled clock or test executor. A single constructor does not need `@Autowired` here.

## How does Spring find classes?

`@SpringBootApplication` combines configuration, auto-configuration, and component scanning.
The application class sits in `com.example.jobscheduler`, so its subpackages are scanned by default.
Classes marked with stereotypes such as `@Service` and `@RestController` become bean candidates.
Spring Data also creates implementations for repository interfaces.

Auto-configuration is conditional: a database dependency and datasource settings can cause Boot
to configure a datasource. It does not automatically invent this project's business rules.
Those rules live in our own services and engine classes.

## Does Spring create a new service for each HTTP request?

Normally no. The shared service handles calls from multiple threads. That is why a service
should not keep one request's mutable data in instance fields. Here, request-specific values
are method arguments and local variables; durable job state lives in PostgreSQL.

## How does configuration reach Java code?

Defaults live in [application.yml](../../src/main/resources/application.yml).
[SchedulerProperties](../../src/main/java/com/example/jobscheduler/config/SchedulerProperties.java)
binds settings such as poll interval and concurrency into a typed record through
`@ConfigurationProperties`.

Environment variables override settings: `SCHEDULER_ROLE=WORKER` selects the worker role.
`DB_URL`, `DB_USER`, and `DB_PASSWORD` feed datasource placeholders. A `.env` file is not
loaded automatically by a host-run Spring Boot process; export it first. Compose has its
own `.env` handling and explicitly passes container environment variables.

## What changes between API, WORKER, and ALL?

[RoleConditions](../../src/main/java/com/example/jobscheduler/config/RoleConditions.java) controls
which beans are registered. API enables business controllers without the job polling loop.
WORKER enables the loop and exposes actuator endpoints, with business routes unavailable.
ALL enables both. The same Java artifact can therefore run in different roles.

A WORKER still has an HTTP server for health and metrics. It does not receive jobs through
that server; it finds due jobs in PostgreSQL.

## Does Spring run the jobs with `@Scheduled`?

The engine uses our own [SchedulerLoop](../../src/main/java/com/example/jobscheduler/engine/SchedulerLoop.java),
which manages polling, capacity, and shutdown. API-side metric refresh uses `@Scheduled`
for housekeeping. That annotation is not the distributed scheduling algorithm.

## Try reading the startup path

Open the application class, `TimeConfiguration`, and
[EngineConfiguration](../../src/main/java/com/example/jobscheduler/config/EngineConfiguration.java),
in that order. Find one discovered bean, one explicit `@Bean`, and the worker-role condition.
Expected observation: `main` is small because Spring assembles the collaborating objects.

**Check your understanding:** If two worker containers run, do they share one `JobService`
object? No. They have separate JVMs and application contexts; they share the database.
