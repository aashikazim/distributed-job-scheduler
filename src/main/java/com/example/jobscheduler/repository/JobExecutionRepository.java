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

    public JobExecutionRepository(JdbcClient jdbc, ScheduleCalculator schedules, BackoffPolicy backoff) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.backoff = backoff;
    }

    /** A delayed submission must still own an unexpired lease before it can execute. */
    @Transactional
    public boolean start(ClaimedJob job, UUID runId, String nodeId) {
        return jdbc.sql("""
                INSERT INTO job_runs (id, job_id, attempt, status, scheduled_for, started_at, node_id)
                SELECT :runId, id, :attempt, 'RUNNING', next_run_at, now(), :node
                FROM jobs WHERE id = :job AND locked_by = :node AND locked_until > now()
                """).param("runId", runId).param("attempt", job.retryCount() + 1)
                .param("node", nodeId).param("job", job.id()).update() == 1;
    }

    /** Renew only an unexpired lease still owned by this process; never resurrect lost ownership. */
    public boolean heartbeat(UUID jobId, String nodeId, Duration lease) {
        return jdbc.sql("""
                UPDATE jobs SET locked_until = now() + make_interval(secs => :seconds)
                WHERE id = :id AND locked_by = :node AND locked_until > now()
                """).param("seconds", lease.toMillis() / 1000.0).param("id", jobId).param("node", nodeId).update() == 1;
    }

    /**
     * Schedule advancement and run outcome commit together. The ownership CAS fences stale
     * workers; an expired owner cannot write job state even before a replacement claims it.
     * Exhausted one-time jobs are dead-lettered; recurring jobs reset retries and advance.
     * A pause issued during execution survives completion.
     */
    @Transactional
    public boolean finish(ClaimedJob job, UUID runId, String node, RunStatus outcome, String summary, String error) {
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
                """).param("status", status).param("next", next == null ? null : next.atOffset(java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("retries", retry ? job.retryCount() + 1 : 0).param("job", job.id()).param("node", node).update();
        jdbc.sql("""
                UPDATE job_runs SET status = :status, finished_at = now(),
                    result_summary = :summary, error_message = :error
                WHERE id = :run AND node_id = :node AND status = 'RUNNING'
                """).param("status", updated == 1 ? outcome.name() : "LOST")
                .param("summary", updated == 1 ? TextLimit.truncate(summary) : null, java.sql.Types.VARCHAR)
                .param("error", updated == 1 ? TextLimit.truncate(error) : "Lease ownership lost", java.sql.Types.VARCHAR)
                .param("run", runId).param("node", node).update();
        return updated == 1;
    }

    public void release(String node) {
        jdbc.sql("UPDATE jobs SET locked_until = now() WHERE locked_by = :node")
                .param("node", node).update();
    }
}
