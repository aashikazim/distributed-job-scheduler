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

@SpringBootTest(properties = {"scheduler.enabled=false", "scheduler.api-key=test-only-key",
        "scheduler.http.allowed-hosts=example.com,127.0.0.1,localhost"})
@Import(EngineIntegrationTest.Executors.class)
class EngineIntegrationTest extends PostgresIntegrationTest {
    private final JdbcTemplate jdbc;
    private final JobClaimRepository claims;
    private final Janitor janitor;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final JobExecutionRepository executions;
    private final ExecutorRegistry registry;
    private final com.example.jobscheduler.config.SchedulerMetrics schedulerMetrics;
    private final RecordingExecutor recorder;
    private final TransactionTemplate transactions;
    private final jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    EngineIntegrationTest(JdbcTemplate jdbc, JobClaimRepository claims, JobExecutionRepository executions,
                          ExecutorRegistry registry, RecordingExecutor recorder, Janitor janitor,
                          com.example.jobscheduler.config.SchedulerMetrics schedulerMetrics, io.micrometer.core.instrument.MeterRegistry metrics,
                          org.springframework.transaction.PlatformTransactionManager manager, jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        this.jdbc = jdbc;
        this.janitor = janitor;
        this.metrics = metrics;
        this.entityManagerFactory = entityManagerFactory;
        this.claims = claims;
        this.executions = executions;
        this.registry = registry;
        this.schedulerMetrics = schedulerMetrics;
        this.recorder = recorder;
        transactions = new TransactionTemplate(manager);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE jobs CASCADE");
        recorder.counts.clear();
        recorder.contexts.clear();
        recorder.interrupted.clear();
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
        final Map<UUID, Map<String, String>> contexts = new ConcurrentHashMap<>();
        final Map<UUID, AtomicInteger> counts = new ConcurrentHashMap<>();
        final Map<UUID, AtomicInteger> interrupted = new ConcurrentHashMap<>();
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
            var mdc = org.slf4j.MDC.getCopyOfContextMap();
            contexts.put(context.jobId(), mdc == null ? Map.of() : Map.copyOf(mdc));
            counts.computeIfAbsent(context.jobId(), id -> new AtomicInteger()).incrementAndGet();
            if (entered != null) {
                entered.countDown();
            }
            var currentGate = gate;
            try {
                if (currentGate != null && !currentGate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test gate not released");
                }
            } catch (InterruptedException ex) {
                interrupted.computeIfAbsent(context.jobId(), id -> new AtomicInteger()).incrementAndGet();
                throw ex;
            }
            if (context.payload().path("fail").asBoolean()
                    || context.attempt() <= context.payload().path("failUntil").asInt()) {
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
        var snapshot = claims.claim("owner", Duration.ofSeconds(10), 1).getFirst();
        var run = UUID.randomUUID();
        executions.start(snapshot, run, "owner");
        transactions.executeWithoutResult(status -> {
            // Load a JPA snapshot, then renew the lease before its eventual dirty flush.
            var manager = org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            var managed = manager.find(com.example.jobscheduler.domain.Job.class, id);
            var originalLease = managed.getLockedUntil();
            assertThat(executions.heartbeat(id, run, "owner", Duration.ofSeconds(30))).isTrue();
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
        assertThat(executions.heartbeat(old.id(), runId, "old", Duration.ofSeconds(10))).isFalse();
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
                Duration.ofMillis(600), Duration.ofMillis(100), Duration.ofSeconds(2),
                Duration.ofMillis(100), 30, Duration.ofSeconds(60));
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

    @Test
    void crashedWorkerIsTakenOverAfterNaturalLeaseExpiryAndOldRunBecomesLost() throws Exception {
        var id = job("crash", -1);
        var properties = new SchedulerProperties(Duration.ofMillis(20), 1, 1,
                Duration.ofMillis(600), Duration.ofMillis(100), Duration.ofSeconds(1),
                Duration.ofMillis(100), 30, Duration.ofSeconds(60));
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(1);
        var snapshot = claims.claim("dead-worker", properties.leaseDuration(), 1).getFirst();
        var deadRunner = new JobRunner(executions, registry, properties, Clock.systemUTC(), "dead-worker", schedulerMetrics);
        var coordinator = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            var task = coordinator.submit(() -> deadRunner.run(snapshot));
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var oldRun = jdbc.queryForObject("SELECT id FROM job_runs WHERE job_id = ?", UUID.class, id);
            // Simulate death: stop execution/heartbeats but perform no completion or lease release.
            deadRunner.close();
            task.get(3, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM job_runs WHERE id = ?", String.class, oldRun)).isEqualTo("RUNNING");
            recorder.gate = null;
            try (var replacement = loop("replacement", properties)) {
                replacement.start();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(status(id)).isEqualTo("COMPLETED"));
                assertThat(jdbc.queryForObject("SELECT status FROM job_runs WHERE id = ?", String.class, oldRun)).isEqualTo("LOST");
                assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'SUCCEEDED'")).isEqualTo(1);
                assertThat(recorder.counts.get(id).get()).isEqualTo(2);
                assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'RUNNING'")).isZero();
            }
        } finally {
            deadRunner.close();
            coordinator.shutdownNow();
        }
    }

    @Test
    void repeatedFailuresHaveGrowingRealDelaysThenDeadLetter() {
        double deadLettersBefore = metrics.counter("scheduler_dead_letter_total").count();
        var id = job("poison", -1);
        jdbc.update("UPDATE jobs SET payload = '{\"fail\":true}', max_retries = 2, backoff_initial_secs = 1 WHERE id = ?", id);
        try (var worker = loop("poison-worker", 1, 1)) {
            worker.start();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(status(id)).isEqualTo("DEAD_LETTER"));
            assertThat(jdbc.queryForList("SELECT attempt FROM job_runs WHERE job_id = ? ORDER BY started_at", Integer.class, id))
                    .containsExactly(1, 2, 3);
            var delays = jdbc.queryForList("""
                    SELECT extract(epoch FROM scheduled_for - lag(finished_at) OVER (ORDER BY started_at)) AS delay
                    FROM job_runs WHERE job_id = ? ORDER BY started_at
                    """, Double.class, id);
            assertThat(delays.get(1)).isBetween(0.899, 1.101);
            assertThat(delays.get(2)).isBetween(1.799, 2.201);
            assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'FAILED'")).isEqualTo(3);
            assertThat(recorder.counts.get(id).get()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT retry_count FROM jobs WHERE id = ?", Integer.class, id)).isEqualTo(2);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(metrics.counter("scheduler_dead_letter_total").count()).isEqualTo(deadLettersBefore + 1));
        }
    }

    @Test
    void successfulRetryResetsCounterAndRecurringExhaustionAdvancesSlot() {
        var success = job("eventual-success", -1);
        jdbc.update("UPDATE jobs SET payload = '{\"failUntil\":1}', backoff_initial_secs = 1 WHERE id = ?", success);
        try (var worker = loop("eventual", 1, 1)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status(success)).isEqualTo("COMPLETED"));
            assertThat(jdbc.queryForObject("SELECT retry_count FROM jobs WHERE id = ?", Integer.class, success)).isZero();
            assertThat(recorder.counts.get(success).get()).isEqualTo(2);
        }
        var recurring = job("recurring-exhaustion", -1);
        jdbc.update("""
                UPDATE jobs SET schedule_type = 'FIXED_RATE', fixed_rate_seconds = 3600,
                    payload = '{"fail":true}', max_retries = 1, backoff_initial_secs = 1 WHERE id = ?
                """, recurring);
        try (var worker = loop("recurring-failure", 1, 1)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(recorder.counts).containsKey(recurring);
                assertThat(recorder.counts.get(recurring).get()).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT status = 'ACTIVE' AND retry_count = 0 AND next_run_at > now() + interval '59 minutes' AND locked_by IS NULL FROM jobs WHERE id = ?", Boolean.class, recurring)).isTrue();
            });
        }
    }

    @Test
    void timeoutInterruptsExecutorAndRecordsTimedOut() throws Exception {
        var id = job("timeout-interrupt", -1);
        jdbc.update("UPDATE jobs SET timeout_seconds = 1, max_retries = 0 WHERE id = ?", id);
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(1);
        try (var worker = loop("timeout-owner", 1, 1)) {
            worker.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(recorder.interrupted).containsKey(id);
                assertThat(status(id)).isEqualTo("DEAD_LETTER");
                assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'TIMED_OUT'")).isEqualTo(1);
            });
        } finally {
            recorder.gate.countDown();
        }
    }

    @Test
    void shutdownAllowsInFlightCompletionAndStopsNewClaims() throws Exception {
        var first = job("shutdown-finishes", -10);
        var queued = job("shutdown-unclaimed", -1);
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(1);
        try (var worker = loop("shutdown-graceful", 1, 1)) {
            worker.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var stopping = CompletableFuture.runAsync(worker::stop);
            await().atMost(Duration.ofSeconds(2)).until(() -> !worker.isRunning());
            assertThat(stopping.isDone()).isFalse();
            recorder.gate.countDown();
            stopping.get(3, TimeUnit.SECONDS);
            assertThat(status(first)).isEqualTo("COMPLETED");
            assertThat(status(queued)).isEqualTo("ACTIVE");
            assertThat(recorder.counts).doesNotContainKey(queued);
            assertThat(count("SELECT count(*) FROM jobs WHERE locked_by IS NOT NULL")).isZero();
        } finally {
            recorder.gate.countDown();
        }
    }

    @Test
    void shutdownDeadlineReleasesLeaseAndAnotherWorkerImmediatelyResumes() throws Exception {
        var id = job("shutdown-released", -1);
        var properties = new SchedulerProperties(Duration.ofMillis(20), 1, 1,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(100),
                Duration.ofMillis(50), 30, Duration.ofSeconds(60));
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(1);
        try (var first = loop("shutdown-forced", properties)) {
            first.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            first.stop();
            assertThat(jdbc.queryForObject("SELECT locked_by IS NULL AND locked_until <= now() FROM jobs WHERE id = ?", Boolean.class, id)).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(recorder.interrupted).containsKey(id));
            recorder.gate = null;
            try (var replacement = loop("shutdown-replacement", 1, 1)) {
                replacement.start();
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(status(id)).isEqualTo("COMPLETED"));
                assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'LOST'")).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'SUCCEEDED'")).isEqualTo(1);
            }
        }
    }

    @Test
    void janitorMarksExpiredAndReplacedOwnersLostWhileKeepingLiveRun() {
        var expired = job("janitor-expired", -1);
        var replaced = job("janitor-replaced", -1);
        var live = job("janitor-live", -1);
        for (var snapshot : claims.claim("owner", Duration.ofSeconds(10), 3)) {
            executions.start(snapshot, UUID.randomUUID(), "owner");
        }
        jdbc.update("UPDATE jobs SET locked_until = now() - interval '1 second' WHERE id = ?", expired);
        jdbc.update("UPDATE jobs SET locked_by = 'replacement' WHERE id = ?", replaced);
        assertThat(janitor.markLost()).isEqualTo(2);
        assertThat(janitor.markLost()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM job_runs WHERE job_id = ?", String.class, live)).isEqualTo("RUNNING");
    }

    @Test
    void retentionDeletesInBatchesAndKeepsRecentOrRunningHistory() {
        var id = job("retention", 3600);
        jdbc.update("""
                INSERT INTO job_runs (id, job_id, attempt, status, scheduled_for, started_at, finished_at, node_id)
                SELECT gen_random_uuid(), ?, 1, 'SUCCEEDED', now() - interval '40 days',
                    now() - interval '40 days', now() - interval '40 days', 'old' FROM generate_series(1, 1005)
                """, id);
        jdbc.update("""
                INSERT INTO job_runs (id, job_id, attempt, status, scheduled_for, started_at, node_id)
                VALUES (gen_random_uuid(), ?, 1, 'RUNNING', now() - interval '40 days', now() - interval '40 days', 'live'),
                    (gen_random_uuid(), ?, 1, 'SUCCEEDED', now(), now(), 'recent')
                """, id, id);
        assertThat(janitor.purge(30)).isEqualTo(1000);
        assertThat(janitor.purge(30)).isEqualTo(5);
        assertThat(janitor.purge(30)).isZero();
        assertThat(count("SELECT count(*) FROM job_runs")).isEqualTo(2);
    }

    @Test
    void sameNodeTakeoverFencesOldRunAndOldClaimSnapshot() {
        job("same-node", -1);
        var old = claims.claim("same", Duration.ofSeconds(10), 1).getFirst();
        var oldRun = UUID.randomUUID();
        executions.start(old, oldRun, "same");
        jdbc.update("UPDATE jobs SET locked_until = now() - interval '1 second' WHERE id = ?", old.id());
        var newer = claims.claim("same", Duration.ofSeconds(10), 1).getFirst();
        assertThat(executions.start(old, UUID.randomUUID(), "same")).isFalse();
        var newRun = UUID.randomUUID();
        assertThat(executions.start(newer, newRun, "same")).isTrue();
        assertThat(executions.heartbeat(old.id(), oldRun, "same", Duration.ofSeconds(10))).isFalse();
        assertThat(executions.finish(old, oldRun, "same", RunStatus.SUCCEEDED, "stale", null)).isFalse();
        assertThat(executions.finish(newer, newRun, "same", RunStatus.SUCCEEDED, "new", null)).isTrue();
        assertThat(status(old.id())).isEqualTo("COMPLETED");
    }

    @Test
    void misfireSkipAdvancesRecurringCompletesOneTimeAndDoesNotDiscardRetries() {
        var once = job("skip-once", -3600);
        var fixed = job("skip-fixed", -3600);
        var cron = job("skip-cron", -3600);
        var retry = job("skip-retry", -3600);
        var recent = job("skip-recent", -1);
        var fire = job("fire-once-now", -3600);
        jdbc.update("UPDATE jobs SET misfire_policy = 'SKIP' WHERE id <> ?", fire);
        jdbc.update("UPDATE jobs SET schedule_type = 'FIXED_RATE', fixed_rate_seconds = 3600 WHERE id = ?", fixed);
        jdbc.update("UPDATE jobs SET schedule_type = 'CRON', cron_expression = '0 0 0 * * *' WHERE id = ?", cron);
        jdbc.update("UPDATE jobs SET retry_count = 1 WHERE id = ?", retry);
        try (var worker = loop("misfire", 10, 10)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(status(once)).isEqualTo("COMPLETED");
                assertThat(status(retry)).isEqualTo("COMPLETED");
                assertThat(status(recent)).isEqualTo("COMPLETED");
                assertThat(status(fire)).isEqualTo("COMPLETED");
                assertThat(jdbc.queryForObject("SELECT next_run_at > now() AND locked_by IS NULL FROM jobs WHERE id = ?", Boolean.class, fixed)).isTrue();
                assertThat(jdbc.queryForObject("SELECT next_run_at > now() AND locked_by IS NULL FROM jobs WHERE id = ?", Boolean.class, cron)).isTrue();
            });
            assertThat(recorder.counts).containsOnlyKeys(retry, recent, fire);
            assertThat(count("SELECT count(*) FROM job_runs")).isEqualTo(3);
        }
    }

    @Test
    void lostOwnershipInterruptsExecutorWithoutOverwritingNewOwner() throws Exception {
        var id = job("lease-lost-active", -1);
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(1);
        var properties = new SchedulerProperties(Duration.ofMillis(20), 1, 1,
                Duration.ofSeconds(3), Duration.ofMillis(100), Duration.ofSeconds(1),
                Duration.ofMillis(100), 30, Duration.ofSeconds(60));
        try (var worker = loop("old-owner", properties)) {
            worker.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE jobs SET locked_by = 'new-owner', locked_until = now() + interval '1 minute' WHERE id = ?", id);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(recorder.interrupted).containsKey(id);
                assertThat(count("SELECT count(*) FROM job_runs WHERE status = 'LOST'")).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT locked_by FROM jobs WHERE id = ?", String.class, id)).isEqualTo("new-owner");
            });
        } finally {
            recorder.gate.countDown();
        }
    }

    @Test
    void executionMetricsAndVirtualThreadMdcTrackRealRun() throws Exception {
        var id = job("observed", -1);
        double before = metrics.counter("scheduler_runs_total", "type", "RECORD", "outcome", "SUCCEEDED").count();
        recorder.gate = new CountDownLatch(1);
        recorder.entered = new CountDownLatch(1);
        try (var worker = loop("observed-node", 1, 1)) {
            worker.start();
            assertThat(recorder.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(metrics.get("scheduler_running").gauge().value()).isEqualTo(1);
            assertThat(recorder.contexts.get(id)).containsEntry("nodeId", "observed-node").containsEntry("jobId", id.toString());
            assertThat(recorder.contexts.get(id).get("runId"))
                    .isEqualTo(jdbc.queryForObject("SELECT id FROM job_runs WHERE job_id = ?", UUID.class, id).toString());
            recorder.gate.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(status(id)).isEqualTo("COMPLETED");
                assertThat(metrics.get("scheduler_running").gauge().value()).isZero();
                assertThat(metrics.counter("scheduler_runs_total", "type", "RECORD", "outcome", "SUCCEEDED").count()).isEqualTo(before + 1);
            });
            assertThat(metrics.get("scheduler_claim_duration").timer().count()).isPositive();
            assertThat(metrics.counter("scheduler_claimed_total").count()).isPositive();
            assertThat(metrics.timer("scheduler_run_duration", "type", "RECORD", "outcome", "SUCCEEDED").count()).isPositive();
        } finally {
            recorder.gate.countDown();
        }
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id);
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
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofMillis(100), 30, Duration.ofSeconds(60)));
    }

    private SchedulerLoop loop(String node, SchedulerProperties properties) {
        return new SchedulerLoop(claims, executions,
                new JobRunner(executions, registry, properties, Clock.systemUTC(), node, schedulerMetrics), properties, node, janitor, schedulerMetrics);
    }
}
