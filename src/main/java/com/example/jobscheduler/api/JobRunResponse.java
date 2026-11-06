package com.example.jobscheduler.api;

import java.time.Instant;
import java.util.UUID;

import com.example.jobscheduler.domain.JobRun;
import com.example.jobscheduler.domain.RunStatus;

public record JobRunResponse(UUID id, UUID jobId, int attempt, RunStatus status, Instant scheduledFor,
                             Instant startedAt, Instant finishedAt, String nodeId,
                             String resultSummary, String errorMessage) {
    public static JobRunResponse from(JobRun run) {
        return new JobRunResponse(run.getId(), run.getJobId(), run.getAttempt(), run.getStatus(),
                run.getScheduledFor(), run.getStartedAt(), run.getFinishedAt(), run.getNodeId(),
                run.getResultSummary(), run.getErrorMessage());
    }
}
