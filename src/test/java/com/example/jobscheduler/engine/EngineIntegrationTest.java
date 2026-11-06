package com.example.jobscheduler.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.jobscheduler.config.SchedulerProperties;
import com.example.jobscheduler.domain.RunStatus;
import com.example.jobscheduler.executor.ExecutionContext;
import com.example.jobscheduler.executor.ExecutionResult;
import com.example.jobscheduler.executor.ExecutorRegistry;
import com.example.jobscheduler.executor.JobExecutor;
import com.example.jobscheduler.repository.JobClaimRepository;
import com.example.jobscheduler.repository.JobExecutionRepository;
import com.example.jobscheduler.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "scheduler.enabled=false")
@Import(EngineIntegrationTest.Executors.class)
class EngineIntegrationTest extends PostgresIntegrationTest {
    private final JdbcTemplate jdbc;
    private final JobClaimRepository claims;
    private final JobExecutionRepository executions;
    private final ExecutorRegistry registry;
    private final RecordingExecutor recorder;
    private final TransactionTemplate transactions;
    private final jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    EngineIntegrationTest(JdbcTemplate jdbc, JobClaimRepository claims, JobExecutionRepository executions,
                          ExecutorRegistry registry, RecordingExecutor recorder,
                          org.springframework.transaction.PlatformTransactionManager manager, jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        this.jdbc = jdbc;
        this.entityManagerFactory = entityManagerFactory;
        this.claims = claims;
        this.executions = executions;
        this.registry = registry;
        this.recorder = recorder;
        transactions = new TransactionTemplate(manager);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE jobs CASCADE");
        recorder.counts.clear();
        recorder.gate = null;
        recorder.entered = null;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Executors {
        @Bean
        RecordingExecutor recordingExecutor() {
            return new RecordingExecutor();
        }
    }

    static class RecordingExecutor implements JobExecutor {
        final Map<UUID, AtomicInteger> counts = new ConcurrentHashMap<>();
        volatile CountDownLatch gate;
        volatile CountDownLatch entered;

        @Override
        public String type() {
            return "RECORD";
        }

        @Override
        public void validatePayload(JsonNode payload) {
        }

        @Override
        public ExecutionResult execute(ExecutionContext context) throws Exception {
            counts.computeIfAbsent(context.jobId(), id -> new AtomicInteger()).incrementAndGet();
            if (entered != null) {
                entered.countDown();
            }
            var currentGate = gate;
            if (currentGate != null && !currentGate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test gate not released");
            }
            if (context.payload().path("fail").asBoolean()) {
                throw new IllegalStateException("intentional failure");
            }
            return new ExecutionResult("recorded");
        }
    }

    @Test
    void claimFiltersDueActiveAndExpiredLeasesAndRespectsLimit() {
        var oldest = job("oldest", -10);
        var expired = job("expired", -5);
        job("future", 3600);
        var paused = job("paused", -1);
        var locked = job("locked", -1);
        jdbc.update("UPDATE jobs SET status = 'PAUSED' WHERE id = ?", paused);
        jdbc.update("UPDATE jobs SET locked_by = 'dead', locked_until = now() - interval '1 second' WHERE id = ?", expired);
        jdbc.update("UPDATE jobs SET locked_by = 'alive', locked_until = now() + interval '1 hour' WHERE id = ?", locked);
        assertThat(claims.claim("node-a", Duration.ofSeconds(10), 1)).extracting(ClaimedJob::id).containsExactly(oldest);
        assertThat(claims.claim("node-b", Duration.ofSeconds(10), 10)).extracting(ClaimedJob::id).containsExactly(expired);
        assertThat(claims.claim("node-c", Duration.ofSeconds(10), 0)).isEmpty();
        assertThat(claims.claim("node-c", Duration.ofSeconds(10), 10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT locked_until > now() AND version = 1 FROM jobs WHERE id = ?", Boolean.class, oldest)).isTrue();
    }

    @Test
    void skipsRowLockedByAnotherTransactionWithoutWaiting() {
        var locked = job("row-lock", -10);
        var available = job("available", -5);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM jobs WHERE id = ? FOR UPDATE", UUID.class, locked);
            var pending = CompletableFuture.supplyAsync(() -> claims.claim("other", Duration.ofSeconds(10), 5));
            assertThatCode(() -> assertThat(pending.get(2, TimeUnit.SECONDS)).extracting(ClaimedJob::id).containsExactly(available))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void twoIndependentLoopsExecute500DueJobsExactlyOnce() throws Exception {
        jdbc.update("""
                INSERT INTO jobs (id, name, job_type, payload, schedule_type, status, next_run_at)
                SELECT gen_random_uuid(), 'concurrent-' || n, 'RECORD', '{}', 'ONE_TIME', 'ACTIVE', now() - interval '1 second'
                FROM generate_series(1, 500) n
                """);
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(40);
        try (var first = loop("node-a", 20, 20); var second = loop("node-b", 20, 20)) {
            first.start();
            second.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            recorder.gate.countDown();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(count("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'")).isEqualTo(500);
                assertThat(first.inFlight() + second.inFlight()).isZero();
            });
            assertThat(recorder.counts).hasSize(500);
            assertThat(recorder.counts.values()).allSatisfy(counter -> assertThat(counter.get()).isEqualTo(1));
            assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'SUCCEEDED'")).isEqualTo(500);
            assertThat(count("SELECT count(DISTINCT node_id) FROM job_runs")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM job_runs")).isEqualTo(500);
            assertThat(count("SELECT count(*) FROM jobs WHERE locked_by IS NOT NULL")).isZero();
        } finally {
            recorder.gate.countDown();
        }
    }

    @Test
    void neverClaimsBeyondAvailableCapacity() throws Exception {
        for (int i = 0; i < 10; i++) {
            job("capacity-" + i, -1);
        }
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(2);
        try (var worker = loop("bounded", 20, 2)) {
            worker.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(count("SELECT count(*) FROM jobs WHERE locked_by = 'bounded'")).isEqualTo(2));
            recorder.gate.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(count("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'")).isEqualTo(10));
        } finally {
            recorder.gate.countDown();
        }
    }

    @Test
    void apiPauseDoesNotRestoreAnOlderHeartbeatLease() {
        var id = job("pause-heartbeat", -1);
        claims.claim("owner", Duration.ofSeconds(10), 1);
        transactions.executeWithoutResult(status -> {
            // Load a JPA snapshot, then renew the lease before its eventual dirty flush.
            var manager = org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            var managed = manager.find(com.example.jobscheduler.domain.Job.class, id);
            var originalLease = managed.getLockedUntil();
            assertThat(executions.heartbeat(id, "owner", Duration.ofSeconds(30))).isTrue();
            managed.setStatus(com.example.jobscheduler.domain.JobStatus.PAUSED);
            manager.flush();
            assertThat(jdbc.queryForObject("SELECT locked_until FROM jobs WHERE id = ?", java.time.OffsetDateTime.class, id).toInstant())
                    .isAfter(originalLease.plusSeconds(10));
        });
    }

    @Test
    void staleCompletionAndHeartbeatCannotOverwriteReplacementOwner() {
        job("fenced", -1);
        var old = claims.claim("old", Duration.ofSeconds(10), 1).getFirst();
        var runId = UUID.randomUUID();
        assertThat(executions.start(old, runId, "old")).isTrue();
        jdbc.update("UPDATE jobs SET locked_until = now() - interval '1 second' WHERE id = ?", old.id());
        assertThat(executions.heartbeat(old.id(), "old", Duration.ofSeconds(10))).isFalse();
        assertThat(claims.claim("new", Duration.ofSeconds(10), 1)).hasSize(1);
        assertThat(executions.finish(old, runId, "old", RunStatus.SUCCEEDED, "late", null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT locked_by FROM jobs WHERE id = ?", String.class, old.id())).isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT status FROM job_runs WHERE id = ?", String.class, runId)).isEqualTo("LOST");
    }

    @Test
    void heartbeatKeepsLongRunningJobFromBeingReclaimed() throws Exception {
        job("heartbeat", -1);
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(1);
        var properties = new SchedulerProperties(Duration.ofMillis(20), 1, 1,
                Duration.ofMillis(600), Duration.ofMillis(100), Duration.ofSeconds(2));
        try (var worker = loop("heartbeat", properties)) {
            worker.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            await().during(Duration.ofMillis(1200)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(claims.claim("competitor", Duration.ofSeconds(1), 1)).isEmpty());
            recorder.gate.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(count("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'")).isEqualTo(1));
        } finally {
            recorder.gate.countDown();
        }
    }

    @Test
    void recordsFailureAndTimeoutAndAppliesRetryOrExhaustionRule() {
        var failed = job("failed", -1);
        jdbc.update("UPDATE jobs SET payload = '{\"fail\":true}', max_retries = 0 WHERE id = ?", failed);
        try (var worker = loop("failing", 1, 1)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(count("SELECT count(*) FROM jobs WHERE status = 'DEAD_LETTER'")).isEqualTo(1));
            assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'FAILED'")).isEqualTo(1);
        }
        var retrying = job("retrying", -1);
        jdbc.update("UPDATE jobs SET payload = '{\"fail\":true}', backoff_initial_secs = 60 WHERE id = ?", retrying);
        try (var worker = loop("retry", 1, 1)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT retry_count = 1 AND next_run_at > now() AND status = 'ACTIVE' FROM jobs WHERE id = ?", Boolean.class, retrying)).isTrue());
        }
        var timedOut = job("timeout", -1);
        jdbc.update("UPDATE jobs SET timeout_seconds = 1, max_retries = 0 WHERE id = ?", timedOut);
        recorder.gate = new CountDownLatch(1);
        try (var worker = loop("timeout", 1, 1)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'TIMED_OUT'")).isEqualTo(1));
        } finally {
            recorder.gate.countDown();
        }
    }

    @Test
    void exhaustedRecurringJobAdvancesScheduleAndPauseSurvivesCompletion() {
        var id = job("recurring", -1);
        jdbc.update("UPDATE jobs SET schedule_type = 'FIXED_RATE', fixed_rate_seconds = 60, max_retries = 0 WHERE id = ?", id);
        var job = claims.claim("recurring", Duration.ofSeconds(10), 1).getFirst();
        var run = UUID.randomUUID();
        executions.start(job, run, "recurring");
        jdbc.update("UPDATE jobs SET status = 'PAUSED' WHERE id = ?", id);
        assertThat(executions.finish(job, run, "recurring", RunStatus.FAILED, null, "failure")).isTrue();
        assertThat(jdbc.queryForObject("SELECT status = 'PAUSED' AND retry_count = 0 AND next_run_at > now() AND locked_by IS NULL FROM jobs WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void cronJobHitsWireMockOnItsNextSlot() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        server.stubFor(post("/cron").willReturn(ok("cron accepted")));
        try (var worker = loop("http-cron", 2, 2)) {
            var id = job("cron-http", 1);
            jdbc.update("""
                    UPDATE jobs SET job_type = 'HTTP', payload = jsonb_build_object('url', ?::text, 'method', 'POST'),
                        schedule_type = 'CRON', cron_expression = '* * * * * *',
                        next_run_at = date_trunc('second', now()) + interval '1 second' WHERE id = ?
                    """, server.baseUrl() + "/cron", id);
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'SUCCEEDED'")).isGreaterThanOrEqualTo(1));
            jdbc.update("UPDATE jobs SET status = 'PAUSED' WHERE id = ?", id);
            server.verify(postRequestedFor(urlEqualTo("/cron")).withHeader("X-Job-Id", equalTo(id.toString()))
                    .withHeader("X-Job-Run-Id", matching("[0-9a-f-]{36}")));
            assertThat(jdbc.queryForObject("SELECT extract(epoch FROM min(started_at - scheduled_for)) FROM job_runs", Double.class))
                    .isBetween(0.0, 2.0);
        } finally {
            server.stop();
        }
    }

    private UUID job(String name, int dueOffsetSeconds) {
        return jdbc.queryForObject("""
                INSERT INTO jobs (id, name, job_type, payload, schedule_type, status, next_run_at)
                VALUES (gen_random_uuid(), ?, 'RECORD', '{}', 'ONE_TIME', 'ACTIVE', now() + make_interval(secs => ?)) RETURNING id
                """, UUID.class, name, dueOffsetSeconds);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private SchedulerLoop loop(String node, int batch, int capacity) {
        return loop(node, new SchedulerProperties(Duration.ofMillis(20), batch, capacity,
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }

    private SchedulerLoop loop(String node, SchedulerProperties properties) {
        return new SchedulerLoop(claims, executions,
                new JobRunner(executions, registry, properties, Clock.systemUTC(), node), properties, node);
    }
}
