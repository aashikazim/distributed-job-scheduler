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
    private final Semaphore slots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("scheduler-poll-", 0).factory());
    private volatile boolean running;

    public SchedulerLoop(JobClaimRepository claims, JobExecutionRepository executions, JobRunner runner,
                         SchedulerProperties properties, String nodeId) {
        this.claims = claims;
        this.executions = executions;
        this.runner = runner;
        this.properties = properties;
        this.nodeId = nodeId;
        slots = new Semaphore(properties.maxConcurrentRuns());
    }

    @Override
    public synchronized void start() {
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
        try {
            claimAndSubmit();
        } catch (Exception ex) {
            log.warn("Polling failed on node {} ({}); retrying", nodeId, ex.getClass().getSimpleName());
        } finally {
            schedule(Math.max(1, (long) (properties.pollInterval().toMillis()
                    * ThreadLocalRandom.current().nextDouble(0.8, 1.2))));
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
            for (var job : claims.claim(nodeId, properties.leaseDuration(), reserved)) {
                workers.submit(() -> {
                    try {
                        runner.run(job);
                    } catch (Exception ex) {
                        log.warn("Run persistence failed for job {} on node {} ({})", job.id(), nodeId, ex.getClass().getSimpleName());
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
        synchronized (this) {
            running = false;
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
            executions.release(nodeId);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
