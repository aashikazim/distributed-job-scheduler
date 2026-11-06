package com.example.jobscheduler.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.jobscheduler.domain.Job;
import com.example.jobscheduler.domain.JobRun;
import com.example.jobscheduler.domain.JobStatus;
import com.example.jobscheduler.domain.MisfirePolicy;
import com.example.jobscheduler.domain.RunStatus;
import com.example.jobscheduler.domain.ScheduleType;
import com.example.jobscheduler.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceTest extends PostgresIntegrationTest {
    private final JobRepository jobs;
    private final JobRunRepository runs;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    PersistenceTest(JobRepository jobs, JobRunRepository runs, EntityManager entityManager, JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.runs = runs;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Test
    void migrationsCreateExpectedSchemaAndIndexes() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name = 'jobs' AND column_name = 'payload'", String.class))
                .isEqualTo("jsonb");
        assertThat(jdbc.queryForList("SELECT DISTINCT data_type FROM information_schema.columns WHERE table_name IN ('jobs', 'job_runs') AND column_name IN ('run_at', 'next_run_at', 'last_run_at', 'locked_until', 'created_at', 'updated_at', 'scheduled_for', 'started_at', 'finished_at')", String.class))
                .containsExactly("timestamp with time zone");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_jobs_due'", String.class))
                .contains("next_run_at", "WHERE", "ACTIVE");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_job_runs_job_started'", String.class))
                .contains("job_id, started_at DESC");
    }

    @Test
    void jobRoundTripsJsonSchedulesDefaultsAndDatabaseAuditTimes() {
        var job = newJob("nightly");
        job.setDescription("Report generation");
        job.setCronExpression("0 0 2 * * *");
        job.setTimezone("America/New_York");
        job.setNextRunAt(Instant.parse("2026-11-06T07:00:00Z"));
        jobs.saveAndFlush(job);
        entityManager.clear();

        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getPayload()).isEqualTo(job.getPayload());
        assertThat(stored.getScheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(stored.getTimezone()).isEqualTo("America/New_York");
        assertThat(stored.getNextRunAt()).isEqualTo(job.getNextRunAt());
        assertThat(stored.getStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(stored.getMaxRetries()).isEqualTo(3);
        assertThat(stored.getRetryCount()).isZero();
        assertThat(stored.getBackoffInitialSecs()).isEqualTo(10);
        assertThat(stored.getBackoffMultiplier()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(stored.getBackoffMaxSecs()).isEqualTo(3600);
        assertThat(stored.getTimeoutSeconds()).isEqualTo(60);
        assertThat(stored.getMisfirePolicy()).isEqualTo(MisfirePolicy.FIRE_ONCE_NOW);
        assertThat(stored.getVersion()).isZero();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
        assertThat(stored.getLockedBy()).isNull();
        assertThat(stored.getLockedUntil()).isNull();
    }

    @Test
    void oneTimeAndFixedRateSchedulesRoundTrip() {
        var once = newJob("once");
        once.setScheduleType(ScheduleType.ONE_TIME);
        once.setRunAt(Instant.parse("2026-12-01T10:00:00Z"));
        once.setNextRunAt(once.getRunAt());
        once.setMisfirePolicy(MisfirePolicy.SKIP);
        var fixed = newJob("fixed");
        fixed.setScheduleType(ScheduleType.FIXED_RATE);
        fixed.setFixedRateSeconds(120);
        fixed.setStatus(JobStatus.PAUSED);
        jobs.saveAndFlush(once);
        jobs.saveAndFlush(fixed);
        entityManager.clear();
        assertThat(jobs.findById(once.getId()).orElseThrow().getRunAt()).isEqualTo(once.getRunAt());
        assertThat(jobs.findById(once.getId()).orElseThrow().getMisfirePolicy()).isEqualTo(MisfirePolicy.SKIP);
        assertThat(jobs.findById(fixed.getId()).orElseThrow().getFixedRateSeconds()).isEqualTo(120);
        assertThat(jobs.findById(fixed.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PAUSED);
    }

    @Test
    void duplicateNameIsRejectedByDatabase() {
        jobs.saveAndFlush(newJob("duplicate"));
        assertThatThrownBy(() -> jobs.saveAndFlush(newJob("duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleDetachedUpdateIsRejected() {
        var stale = jobs.saveAndFlush(newJob("versioned"));
        entityManager.clear();
        var current = jobs.findById(stale.getId()).orElseThrow();
        current.setDescription("new version");
        jobs.saveAndFlush(current);
        entityManager.clear();
        stale.setDescription("stale write");
        assertThatThrownBy(() -> jobs.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void runHistoryIsScopedPaginatedAndOrdered() {
        var job = jobs.saveAndFlush(newJob("history"));
        var other = jobs.saveAndFlush(newJob("other"));
        var start = Instant.parse("2026-11-05T12:00:00Z");
        runs.saveAndFlush(new JobRun(job.getId(), 1, start, start, "worker-a"));
        var latest = new JobRun(job.getId(), 2, start, start.plusSeconds(10), "worker-b");
        latest.setStatus(RunStatus.SUCCEEDED);
        latest.setFinishedAt(start.plusSeconds(11));
        latest.setResultSummary("HTTP 200");
        runs.saveAndFlush(latest);
        runs.saveAndFlush(new JobRun(other.getId(), 1, start, start.plusSeconds(20), "worker-a"));
        entityManager.clear();

        var page = runs.findByJobIdOrderByStartedAtDesc(job.getId(), PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(2);
        var stored = page.getContent().getFirst();
        assertThat(stored.getId()).isEqualTo(latest.getId());
        assertThat(stored.getAttempt()).isEqualTo(2);
        assertThat(stored.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stored.getNodeId()).isEqualTo("worker-b");
        assertThat(stored.getScheduledFor()).isEqualTo(start);
        assertThat(stored.getFinishedAt()).isEqualTo(start.plusSeconds(11));
        assertThat(stored.getResultSummary()).isEqualTo("HTTP 200");
    }

    @Test
    void deletingJobCascadesToRunHistory() {
        var job = jobs.saveAndFlush(newJob("deleted"));
        var run = runs.saveAndFlush(new JobRun(job.getId(), 1, Instant.now(), Instant.now(), "worker"));
        jobs.delete(job);
        jobs.flush();
        entityManager.clear();
        assertThat(runs.findById(run.getId())).isEmpty();
    }

    @Test
    void runCannotReferenceMissingJob() {
        var run = new JobRun(UUID.randomUUID(), 1, Instant.now(), Instant.now(), "worker");
        assertThatThrownBy(() -> runs.saveAndFlush(run))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Job newJob(String name) {
        return new Job(name, "HTTP", json.createObjectNode().put("url", "https://example.com/hook"), ScheduleType.CRON);
    }
}
