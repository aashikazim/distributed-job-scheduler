package com.example.jobscheduler.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.jobscheduler.domain.ScheduleType;
import com.example.jobscheduler.domain.MisfirePolicy;
import com.fasterxml.jackson.databind.JsonNode;

/** Immutable execution snapshot, detached from JPA and the short claim transaction. */
public record ClaimedJob(UUID id, String jobType, JsonNode payload, ScheduleType scheduleType,
                         String cronExpression, Integer fixedRateSeconds, Instant runAt, String timezone,
                         Instant nextRunAt, int retryCount, int maxRetries, int timeoutSeconds,
                         int backoffInitialSecs, BigDecimal backoffMultiplier, int backoffMaxSecs,
                         MisfirePolicy misfirePolicy, Instant claimedAt, long claimVersion) {
}
