package com.example.jobscheduler.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.jobscheduler.domain.Job;
import com.example.jobscheduler.domain.JobStatus;
import com.example.jobscheduler.domain.MisfirePolicy;
import com.example.jobscheduler.domain.ScheduleType;
import com.fasterxml.jackson.databind.JsonNode;

public record JobResponse(
        UUID id, String name, String description, String jobType, JsonNode payload,
        ScheduleType scheduleType, String cronExpression, Integer fixedRateSeconds, Instant runAt,
        String timezone, JobStatus status, Instant nextRunAt, Instant lastRunAt,
        int maxRetries, int retryCount, int backoffInitialSecs, BigDecimal backoffMultiplier,
        int backoffMaxSecs, int timeoutSeconds, MisfirePolicy misfirePolicy,
        Long version, Instant createdAt, Instant updatedAt) {

    public static JobResponse from(Job job) {
        return new JobResponse(job.getId(), job.getName(), job.getDescription(), job.getJobType(),
                job.getPayload(), job.getScheduleType(), job.getCronExpression(), job.getFixedRateSeconds(),
                job.getRunAt(), job.getTimezone(), job.getStatus(), job.getNextRunAt(), job.getLastRunAt(),
                job.getMaxRetries(), job.getRetryCount(), job.getBackoffInitialSecs(), job.getBackoffMultiplier(),
                job.getBackoffMaxSecs(), job.getTimeoutSeconds(), job.getMisfirePolicy(),
                job.getVersion(), job.getCreatedAt(), job.getUpdatedAt());
    }
}
