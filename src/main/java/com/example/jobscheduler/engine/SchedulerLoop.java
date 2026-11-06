package com.example.jobscheduler.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.example.jobscheduler.config.SchedulerProperties;
import com.example.jobscheduler.repository.JobClaimRepository;
import com.example.jobscheduler.repository.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class SchedulerLoop implements SmartLifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);
    private final JobClaimRepository claims;
    private final JobExecutionRepository executions;
    private final JobRunner runner;
    private final SchedulerProperties properties;
    private final String nodeId;
    private final Janitor janitor;
    private final com.example.jobscheduler.config.SchedulerMetrics metrics;
    private final PollingBackoff backoff;
    private long nextCleanup;
    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
    private final Semaphore slots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("scheduler-poll-", 0).factory());
    private volatile boolean running;

    public SchedulerLoop(JobClaimRepository claims, JobExecutionRepository executions, JobRunner runner,
                         SchedulerProperties properties, String nodeId, Janitor janitor, com.example.jobscheduler.config.SchedulerMetrics metrics) {
        this.claims = claims;
        this.executions = executions;
        this.runner = runner;
        this.properties = properties;
        this.nodeId = nodeId;
        this.janitor = janitor;
        this.metrics = metrics;
        backoff = new PollingBackoff(properties.pollInterval());
        slots = new Semaphore(properties.maxConcurrentRuns());
    }

    @Override
    public synchronized void start() {
        if (stopped.get()) {
            throw new IllegalStateException("A stopped scheduler cannot be restarted");
        }
        if (!running) {
            running = true;
            schedule(0);
        }
    }

    private void schedule(long delay) {
        if (running) {
            polling.schedule(this::poll, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void poll() {
        try (var context = new com.example.jobscheduler.util.LogContext(nodeId, null, null)) {
            pollWithContext();
        }
    }

    private void pollWithContext() {
        long delay;
        try {
            metrics.refreshBacklog();
            long now = System.nanoTime(); // Local elapsed cadence only; lease decisions use PostgreSQL now().
            if (nextCleanup == 0 || now - nextCleanup >= 0) {
                janitor.markLost();
                janitor.purge(properties.runRetentionDays());
                nextCleanup = now + properties.janitorInterval().toNanos();
            }
            claimAndSubmit();
            delay = backoff.success();
        } catch (Exception ex) {
            delay = backoff.failure();
            log.warn("Polling failed on node {} ({}); backing off", nodeId, ex.getClass().getSimpleName());
        }
        synchronized (this) {
            // Serialize with shutdown: no rejected scheduling race after polling.shutdownNow().
            schedule(Math.max(1, (long) (delay * ThreadLocalRandom.current().nextDouble(0.8, 1.2))));
        }
    }

    /** Reserve local capacity before claiming, so no leased job waits in a work queue. */
    private synchronized void claimAndSubmit() {
        if (!running) {
            return;
        }
        int reserved = Math.min(properties.batchSize(), slots.availablePermits());
        if (reserved == 0 || !slots.tryAcquire(reserved)) {
            return;
        }
        int submitted = 0;
        try {
            java.util.List<ClaimedJob> claimed;
            var sample = metrics.startClaim();
            try {
                claimed = claims.claim(nodeId, properties.leaseDuration(), reserved);
            } finally {
                metrics.stopClaim(sample);
            }
            metrics.claimed(claimed.size());
            for (var job : claimed) {
                workers.submit(() -> {
                    try (var context = new com.example.jobscheduler.util.LogContext(nodeId, job.id(), null)) {
                        runner.run(job);
                    } catch (Exception ex) {
                        try (var context = new com.example.jobscheduler.util.LogContext(nodeId, job.id(), null)) {
                            log.warn("Run persistence failed ({})", ex.getClass().getSimpleName());
                        }
                    } finally {
                        slots.release();
                    }
                });
                submitted++;
            }
        } finally {
            slots.release(reserved - submitted);
        }
    }

    public int inFlight() {
        return properties.maxConcurrentRuns() - slots.availablePermits();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        try (var context = new com.example.jobscheduler.util.LogContext(nodeId, null, null)) {
            stopWithContext();
        }
    }

    private void stopWithContext() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // Publish first: a poll finishing its DB call must not schedule another iteration.
        running = false;
        synchronized (this) {
            polling.shutdownNow();
            workers.shutdown();
        }
        try {
            if (!workers.awaitTermination(properties.shutdownGracePeriod().toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException ex) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            runner.close();
            try {
                executions.release(nodeId);
            } catch (Exception ex) {
                // PostgreSQL may be down during shutdown. Existing leases still expire naturally.
                log.warn("Lease release failed on node {} ({}); leases will expire", nodeId, ex.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}
