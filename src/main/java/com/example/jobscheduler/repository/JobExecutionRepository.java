package com.example.jobscheduler.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.jobscheduler.domain.RunStatus;
import com.example.jobscheduler.domain.ScheduleType;
import com.example.jobscheduler.engine.ClaimedJob;
import com.example.jobscheduler.service.BackoffPolicy;
import com.example.jobscheduler.service.ScheduleCalculator;
import com.example.jobscheduler.util.TextLimit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobExecutionRepository {
    private final JdbcClient jdbc;
    private final ScheduleCalculator schedules;
    private final BackoffPolicy backoff;
    private final io.micrometer.core.instrument.Counter deadLetters;

    public JobExecutionRepository(JdbcClient jdbc, ScheduleCalculator schedules, BackoffPolicy backoff,
                                  io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.backoff = backoff;
        deadLetters = metrics.counter("scheduler_dead_letter_total");
    }

    /** A delayed submission must still own an unexpired lease before it can execute. */
    @Transactional
    public boolean start(ClaimedJob job, UUID runId, String nodeId) {
        var owned = jdbc.sql("""
                SELECT id FROM jobs WHERE id = :job AND locked_by = :node AND locked_until > now()
                    AND version = :version FOR UPDATE
                """).param("job", job.id()).param("node", nodeId).param("version", job.claimVersion())
                .query(UUID.class).optional();
        if (owned.isEmpty()) {
            return false;
        }
        // Serialize with claims/janitor and fence abandoned attempts, including same-node takeovers.
        jdbc.sql("""
                UPDATE job_runs SET status = 'LOST', finished_at = now(), error_message = 'Lease ownership lost'
                WHERE job_id = :job AND status = 'RUNNING'
                """).param("job", job.id()).update();
        return jdbc.sql("""
                INSERT INTO job_runs (id, job_id, attempt, status, scheduled_for, started_at, node_id)
                VALUES (:runId, :job, :attempt, 'RUNNING', :scheduled, now(), :node)
                """).param("runId", runId).param("attempt", job.retryCount() + 1)
                .param("scheduled", job.nextRunAt().atOffset(java.time.ZoneOffset.UTC))
                .param("node", nodeId).param("job", job.id()).update() == 1;
    }

    /** Renew only an unexpired lease still owned by this process; never resurrect lost ownership. */
    @Transactional
    public boolean heartbeat(UUID jobId, UUID runId, String nodeId, Duration lease) {
        // Acquire the parent lock first. The following statement sees a fresh run-status snapshot
        // after any competing claim commits; a same-node replacement must fence this run too.
        if (jdbc.sql("SELECT id FROM jobs WHERE id = :id AND locked_by = :node AND locked_until > now() FOR UPDATE")
                .param("id", jobId).param("node", nodeId).query(UUID.class).optional().isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                UPDATE jobs SET locked_until = now() + make_interval(secs => :seconds)
                WHERE id = :id AND locked_by = :node AND locked_until > now()
                    AND EXISTS (SELECT 1 FROM job_runs WHERE id = :run AND job_id = :id
                        AND node_id = :node AND status = 'RUNNING')
                """).param("seconds", lease.toMillis() / 1000.0).param("id", jobId).param("run", runId).param("node", nodeId).update() == 1;
    }

    /**
     * Schedule advancement and run outcome commit together. The ownership CAS fences stale
     * workers; an expired owner cannot write job state even before a replacement claims it.
     * Exhausted one-time jobs are dead-lettered; recurring jobs reset retries and advance.
     * A pause issued during execution survives completion.
     */
    @Transactional
    public boolean finish(ClaimedJob job, UUID runId, String node, RunStatus outcome, String summary, String error) {
        var owned = jdbc.sql("SELECT id FROM jobs WHERE id = :id AND locked_by = :node AND locked_until > now() FOR UPDATE")
                .param("id", job.id()).param("node", node).query(UUID.class).optional();
        if (owned.isEmpty()) {
            markLost(runId, node);
            return false;
        }
        var now = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
        boolean success = outcome == RunStatus.SUCCEEDED;
        boolean retry = !success && job.retryCount() < job.maxRetries();
        boolean terminal = job.scheduleType() == ScheduleType.ONE_TIME && !retry;
        Instant next = null;
        if (retry) {
            next = now.plusMillis((long) (backoff.seconds(job) * 1000));
        } else if (!terminal) {
            next = schedules.next(job.scheduleType(), job.cronExpression(), job.fixedRateSeconds(),
                    job.runAt(), job.timezone(), now);
        }
        var status = terminal ? (success ? "COMPLETED" : "DEAD_LETTER") : "ACTIVE";
        int updated = jdbc.sql("""
                UPDATE jobs SET
                    status = CASE WHEN status = 'PAUSED' THEN 'PAUSED' ELSE :status END,
                    next_run_at = :next, last_run_at = now(), retry_count = :retries,
                    locked_by = NULL, locked_until = NULL, updated_at = now(), version = version + 1
                WHERE id = :job AND locked_by = :node AND locked_until > now()
                    AND EXISTS (SELECT 1 FROM job_runs WHERE id = :run AND job_id = :job
                        AND node_id = :node AND status = 'RUNNING')
                """).param("run", runId).param("status", status).param("next", next == null ? null : next.atOffset(java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("retries", retry ? job.retryCount() + 1 : (success || !terminal ? 0 : job.retryCount())).param("job", job.id()).param("node", node).update();
        jdbc.sql("""
                UPDATE job_runs SET status = :status, finished_at = now(),
                    result_summary = :summary, error_message = :error
                WHERE id = :run AND node_id = :node AND status = 'RUNNING'
                """).param("status", updated == 1 ? outcome.name() : "LOST")
                .param("summary", updated == 1 ? TextLimit.truncate(summary) : null, java.sql.Types.VARCHAR)
                .param("error", updated == 1 ? TextLimit.truncate(error) : "Lease ownership lost", java.sql.Types.VARCHAR)
                .param("run", runId).param("node", node).update();
        if (updated == 1 && terminal && !success) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deadLetters.increment();
                        }
                    });
        }
        return updated == 1;
    }

    private void markLost(UUID runId, String node) {
        jdbc.sql("""
                UPDATE job_runs SET status = 'LOST', finished_at = now(), error_message = 'Lease ownership lost'
                WHERE id = :run AND node_id = :node AND status = 'RUNNING'
                """).param("run", runId).param("node", node).update();
    }

    /** Skip stale schedule slots without creating an execution or consuming a retry. */
    @Transactional
    public boolean skipMisfire(ClaimedJob job, String node) {
        var now = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
        boolean terminal = job.scheduleType() == ScheduleType.ONE_TIME;
        var next = terminal ? null : schedules.next(job.scheduleType(), job.cronExpression(),
                job.fixedRateSeconds(), job.runAt(), job.timezone(), now);
        int updated = jdbc.sql("""
                UPDATE jobs SET next_run_at = :next,
                    status = CASE WHEN status = 'PAUSED' THEN 'PAUSED' ELSE :status END,
                    locked_by = NULL, locked_until = NULL, updated_at = now(), version = version + 1
                WHERE id = :job AND locked_by = :node AND locked_until > now() AND version = :version
                """).param("next", next == null ? null : next.atOffset(java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("status", terminal ? "COMPLETED" : "ACTIVE").param("job", job.id())
                .param("node", node).param("version", job.claimVersion()).update();
        if (updated == 1) {
            jdbc.sql("""
                    UPDATE job_runs SET status = 'LOST', finished_at = now(), error_message = 'Lease ownership lost'
                    WHERE job_id = :job AND status = 'RUNNING'
                    """).param("job", job.id()).update();
        }
        return updated == 1;
    }

    public void release(String node) {
        // Clearing owner fences a heartbeat already in flight when shutdown released the lease.
        jdbc.sql("UPDATE jobs SET locked_until = now(), locked_by = NULL, updated_at = now(), version = version + 1 WHERE locked_by = :node")
                .param("node", node).update();
    }
}
