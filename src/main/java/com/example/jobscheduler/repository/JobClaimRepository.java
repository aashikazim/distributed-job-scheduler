package com.example.jobscheduler.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.jobscheduler.domain.ScheduleType;
import com.example.jobscheduler.engine.ClaimedJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobClaimRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JobClaimRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Locks only the chosen rows, skips rows another transaction is claiming, and commits
     * before execution. The persistent lease bridges that transaction boundary. Advancing
     * version also rejects a concurrent JPA update based on a pre-claim snapshot.
     */
    @Transactional
    public List<ClaimedJob> claim(String nodeId, Duration lease, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return jdbc.sql("""
                UPDATE jobs
                SET locked_by = :nodeId,
                    locked_until = now() + make_interval(secs => :leaseSeconds),
                    updated_at = now(), version = version + 1
                WHERE id IN (
                    SELECT id FROM jobs
                    WHERE status = 'ACTIVE'
                      AND next_run_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY next_run_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """)
                .param("nodeId", nodeId).param("leaseSeconds", lease.toMillis() / 1000.0)
                .param("batchSize", limit).query((rs, row) -> map(rs)).list();
    }

    private ClaimedJob map(ResultSet rs) throws SQLException {
        try {
            return new ClaimedJob(rs.getObject("id", UUID.class), rs.getString("job_type"),
                    json.readTree(rs.getString("payload")), ScheduleType.valueOf(rs.getString("schedule_type")),
                    rs.getString("cron_expression"), rs.getObject("fixed_rate_seconds", Integer.class),
                    instant(rs, "run_at"), rs.getString("timezone"), instant(rs, "next_run_at"),
                    rs.getInt("retry_count"), rs.getInt("max_retries"), rs.getInt("timeout_seconds"),
                    rs.getInt("backoff_initial_secs"), rs.getBigDecimal("backoff_multiplier"), rs.getInt("backoff_max_secs"));
        } catch (JsonProcessingException ex) {
            throw new SQLException("Stored job payload is invalid JSON", ex);
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
