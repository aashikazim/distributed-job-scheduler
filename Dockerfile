# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
# The wrapper pins the ZIP checksum; unzip prevents fallback to a different tarball.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp dependency:go-offline
COPY src src
# Integration tests run with ./mvnw verify on the host/CI Docker daemon, outside image construction.
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 scheduler \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin scheduler
WORKDIR /app
USER 10001:10001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# Optional test receiver, built from the same JDK-only test support; never part of the app image.
FROM runtime AS smoke-target
COPY --from=build /build/target/test-classes/com/example/jobscheduler/support/SmokeWebhookTarget*.class /smoke/com/example/jobscheduler/support/
HEALTHCHECK --interval=2s --timeout=2s --start-period=5s CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/smoke", "com.example.jobscheduler.support.SmokeWebhookTarget"]

FROM runtime AS app
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.source="https://github.com/aashikazim/distributed-job-scheduler" \
      org.opencontainers.image.revision="${GIT_SHA}"
COPY --from=build --chown=10001:10001 /build/target/job-scheduler-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chmod=755 docker/entrypoint.sh /app/entrypoint.sh
HEALTHCHECK --interval=5s --timeout=3s --start-period=45s --retries=12 CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
