package com.example.jobscheduler.engine;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.jobscheduler.config.SchedulerProperties;
import com.example.jobscheduler.domain.MisfirePolicy;
import com.example.jobscheduler.domain.RunStatus;
import com.example.jobscheduler.executor.ExecutionContext;
import com.example.jobscheduler.executor.ExecutionResult;
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
    private final com.example.jobscheduler.config.SchedulerMetrics metrics;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().daemon().name("job-heartbeat-", 0).factory());

    public JobRunner(JobExecutionRepository executions, ExecutorRegistry executors, SchedulerProperties properties,
                     Clock clock, String nodeId, com.example.jobscheduler.config.SchedulerMetrics metrics) {
        this.executions = executions;
        this.executors = executors;
        this.properties = properties;
        this.clock = clock;
        this.nodeId = nodeId;
        this.metrics = metrics;
    }

    public void run(ClaimedJob job) {
        if (closed.get()) {
            return;
        }
        if (job.retryCount() == 0 && job.misfirePolicy() == MisfirePolicy.SKIP
                && job.nextRunAt().plus(properties.misfireThreshold()).isBefore(job.claimedAt())) {
            executions.skipMisfire(job, nodeId);
            return;
        }
        var runId = UUID.randomUUID();
        if (!executions.start(job, runId, nodeId) || closed.get()) {
            return;
        }
        var sample = metrics.startRun();
        String outcome = "LOST";
        try (var logContext = new com.example.jobscheduler.util.LogContext(nodeId, job.id(), runId)) {
            log.info("Job attempt started");
            outcome = execute(job, runId);
            log.info("Job attempt finished with outcome {}", outcome);
        } finally {
            metrics.finishRun(sample, job.jobType(), outcome);
        }
    }

    private String execute(ClaimedJob job, UUID runId) {
        var lost = new AtomicBoolean();
        var context = new ExecutionContext(job.id(), runId, job.retryCount() + 1, job.payload(),
                clock.instant().plusSeconds(job.timeoutSeconds()));
        Future<ExecutionResult> work = null;
        ScheduledFuture<?> heartbeat = null;
        RunStatus outcome;
        String summary = null;
        String error = null;
        try {
            work = executor.submit(() -> {
                try (var logContext = new com.example.jobscheduler.util.LogContext(nodeId, job.id(), runId)) {
                    return executors.get(job.jobType()).execute(context);
                }
            });
            var activeWork = work;
            heartbeat = heartbeats.scheduleWithFixedDelay(() -> renew(job, runId, activeWork, lost),
                    properties.heartbeatInterval().toMillis(), properties.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
            summary = work.get(job.timeoutSeconds(), TimeUnit.SECONDS).summary();
            outcome = RunStatus.SUCCEEDED;
        } catch (TimeoutException ex) {
            outcome = RunStatus.TIMED_OUT;
            error = "Execution deadline exceeded";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Shutdown leaves this attempt for the janitor; lease release enables immediate takeover.
            return "LOST";
        } catch (Exception ex) {
            var cause = ex instanceof ExecutionException ? ex.getCause() : ex;
            outcome = cause instanceof TimeoutException || cause instanceof HttpTimeoutException
                    ? RunStatus.TIMED_OUT : RunStatus.FAILED;
            // Exception messages from plugins may contain URLs or credentials.
            error = "Executor failed: " + cause.getClass().getSimpleName();
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (work != null) {
                work.cancel(true);
            }
        }
        if (!lost.get() && !closed.get()) {
            return executions.finish(job, runId, nodeId, outcome, summary, error) ? outcome.name() : "LOST";
        }
        return "LOST";
    }

    /** Losing or being unable to verify ownership cancels execution; expiry enables recovery. */
    private void renew(ClaimedJob job, UUID runId, Future<?> work, AtomicBoolean lost) {
        if (closed.get() || lost.get()) {
            return;
        }
        try (var context = new com.example.jobscheduler.util.LogContext(nodeId, job.id(), runId)) {
            if (!executions.heartbeat(job.id(), runId, nodeId, properties.leaseDuration())) {
                lost.set(true);
                work.cancel(true);
            }
        } catch (Exception ex) {
            lost.set(true);
            work.cancel(true);
            try (var context = new com.example.jobscheduler.util.LogContext(nodeId, job.id(), runId)) {
                log.warn("Heartbeat failed ({})", ex.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        heartbeats.shutdownNow();
        executor.shutdownNow();
    }
}
