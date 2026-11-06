package com.example.jobscheduler.config;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.jobscheduler.executor.ExecutorRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SchedulerMetrics {
    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final AtomicReference<Backlog> backlog = new AtomicReference<>(new Backlog(0, 0));
    private final AtomicInteger running = new AtomicInteger();
    private final Timer claimDuration;
    private final Counter claimed;

    public SchedulerMetrics(MeterRegistry registry, JdbcClient jdbc, ExecutorRegistry executors) {
        this.registry = registry;
        this.jdbc = jdbc;
        claimDuration = registry.timer("scheduler_claim_duration");
        claimed = registry.counter("scheduler_claimed_total");
        registry.counter("scheduler_dead_letter_total");
        Gauge.builder("scheduler_jobs_due", backlog, value -> value.get().due()).register(registry);
        Gauge.builder("scheduler_lag_seconds", backlog, value -> value.get().lagSeconds()).register(registry);
        Gauge.builder("scheduler_running", running, AtomicInteger::get).register(registry);
        for (var type : executors.types()) {
            for (var outcome : new String[]{"SUCCEEDED", "FAILED", "TIMED_OUT", "LOST"}) {
                runTimer(type, outcome);
                runCounter(type, outcome);
            }
        }
    }

    /** Refresh using one database-clock snapshot; scraping never opens a DB connection. */
    public void refreshBacklog() {
        var snapshot = jdbc.sql("""
                SELECT count(*) AS due,
                    COALESCE(EXTRACT(EPOCH FROM now() - min(next_run_at)), 0) AS lag
                FROM jobs WHERE status = 'ACTIVE' AND next_run_at <= now()
                """).query((rs, row) -> new Backlog(rs.getLong("due"), Math.max(0, rs.getDouble("lag")))).single();
        backlog.set(snapshot);
    }

    public Timer.Sample startClaim() {
        return Timer.start(registry);
    }

    public void stopClaim(Timer.Sample sample) {
        sample.stop(claimDuration);
    }

    public void claimed(int count) {
        claimed.increment(count);
    }

    public Timer.Sample startRun() {
        running.incrementAndGet();
        return Timer.start(registry);
    }

    public void finishRun(Timer.Sample sample, String type, String outcome) {
        sample.stop(runTimer(type, outcome));
        runCounter(type, outcome).increment();
        running.decrementAndGet();
    }

    private Timer runTimer(String type, String outcome) {
        return registry.timer("scheduler_run_duration", "type", type, "outcome", outcome);
    }

    private Counter runCounter(String type, String outcome) {
        return registry.counter("scheduler_runs_total", "type", type, "outcome", outcome);
    }

    private record Backlog(long due, double lagSeconds) { }
}
