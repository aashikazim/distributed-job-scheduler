# How it works

## Phase 0 — Application skeleton

Spring Boot starts the web server and wires the application components together.
The Maven Wrapper gives every developer the same Maven version; Java 21 is still required.
PostgreSQL runs separately in Docker and keeps its data in a named volume.
Configuration comes from application.yml, with environment variables overriding defaults.
Passwords come from the environment, while .env is ignored by Git.
The UTC Clock bean gives future services a replaceable source of Java-side time.
Actuator health reports whether the application and its database connection are healthy.
The key concept: the application is disposable; durable state belongs in PostgreSQL.
