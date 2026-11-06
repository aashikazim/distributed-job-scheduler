package com.example.jobscheduler.engine;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.jobscheduler.config.SchedulerProperties;
import com.example.jobscheduler.domain.RunStatus;
import com.example.jobscheduler.executor.ExecutionContext;
import com.example.jobscheduler.executor.ExecutorRegistry;
import com.example.jobscheduler.repository.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private final JobExecutionRepository executions;
    private final ExecutorRegistry executors;
    private final SchedulerProperties properties;
    private final Clock clock;
    private final String nodeId;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().daemon().name("job-heartbeat-", 0).factory());

    public JobRunner(JobExecutionRepository executions, ExecutorRegistry executors, SchedulerProperties properties,
                     Clock clock, String nodeId) {
        this.executions = executions;
        this.executors = executors;
        this.properties = properties;
        this.clock = clock;
        this.nodeId = nodeId;
    }

    public void run(ClaimedJob job) {
        var runId = UUID.randomUUID();
        if (!executions.start(job, runId, nodeId)) {
            return;
        }
        var lost = new AtomicBoolean();
        var context = new ExecutionContext(job.id(), runId, job.retryCount() + 1, job.payload(),
                clock.instant().plusSeconds(job.timeoutSeconds()));
        var work = executor.submit(() -> executors.get(job.jobType()).execute(context));
        var heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try {
                if (!executions.heartbeat(job.id(), nodeId, properties.leaseDuration())) {
                    lost.set(true);
                    work.cancel(true);
                }
            } catch (Exception ex) {
                // An uncertain lease is unsafe to keep executing; expiry enables another worker.
                lost.set(true);
                work.cancel(true);
                log.warn("Heartbeat failed for job {} on node {} ({})", job.id(), nodeId, ex.getClass().getSimpleName());
            }
        }, properties.heartbeatInterval().toMillis(), properties.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        RunStatus outcome;
        String summary = null;
        String error = null;
        try {
            summary = work.get(job.timeoutSeconds(), TimeUnit.SECONDS).summary();
            outcome = RunStatus.SUCCEEDED;
        } catch (TimeoutException ex) {
            outcome = RunStatus.TIMED_OUT;
            error = "Execution deadline exceeded";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Leave RUNNING for the later recovery janitor; never claim success during shutdown.
            return;
        } catch (Exception ex) {
            var cause = ex instanceof ExecutionException ? ex.getCause() : ex;
            outcome = cause instanceof TimeoutException || cause instanceof HttpTimeoutException
                    ? RunStatus.TIMED_OUT : RunStatus.FAILED;
            // Exception messages from plugins may contain URLs or credentials.
            error = "Executor failed: " + cause.getClass().getSimpleName();
        } finally {
            heartbeat.cancel(false);
            work.cancel(true);
        }
        if (!lost.get()) {
            executions.finish(job, runId, nodeId, outcome, summary, error);
        }
    }

    @Override
    public void close() {
        heartbeats.shutdownNow();
        executor.shutdownNow();
    }
}
