package com.example.jobscheduler.engine;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Janitor {
    public static final int BATCH_SIZE = 1000;
    private final JdbcClient jdbc;

    public Janitor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lock job and run together so a competing claim/completion cannot invalidate the
     * ownership check. SKIP LOCKED allows many pods to clean up without waiting on each other.
     * A replacement can already own a fresh lease: its old owner's run is still LOST.
     */
    @Transactional
    public int markLost() {
        return jdbc.sql("""
                WITH abandoned AS (
                    SELECT r.id FROM job_runs r JOIN jobs j ON j.id = r.job_id
                    WHERE r.status = 'RUNNING'
                      AND (j.locked_by IS DISTINCT FROM r.node_id
                           OR j.locked_until IS NULL OR j.locked_until <= now())
                    ORDER BY r.started_at LIMIT :batch
                    FOR UPDATE OF j, r SKIP LOCKED
                )
                UPDATE job_runs SET status = 'LOST', finished_at = now(), error_message = 'Lease ownership lost'
                WHERE id IN (SELECT id FROM abandoned) AND status = 'RUNNING'
                """).param("batch", BATCH_SIZE).update();
    }

    /** Bound each transaction; retain live runs even if their start is older than retention. */
    @Transactional
    public int purge(int retentionDays) {
        return jdbc.sql("""
                DELETE FROM job_runs WHERE id IN (
                    SELECT id FROM job_runs
                    WHERE status <> 'RUNNING' AND started_at < now() - make_interval(days => :days)
                    ORDER BY started_at LIMIT :batch FOR UPDATE SKIP LOCKED
                )
                """).param("days", retentionDays).param("batch", BATCH_SIZE).update();
    }
}
