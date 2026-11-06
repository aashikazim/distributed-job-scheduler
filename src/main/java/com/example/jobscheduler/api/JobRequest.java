package com.example.jobscheduler.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.jobscheduler.domain.MisfirePolicy;
import com.example.jobscheduler.domain.ScheduleType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/** Full job definition for POST/PUT. Omitted optional settings use the schema defaults. */
public record JobRequest(
        @NotBlank @Size(max = 200) String name,
        String description,
        @NotBlank @Size(max = 50) String jobType,
        @NotNull JsonNode payload,
        @NotNull ScheduleType scheduleType,
        @Size(max = 100) String cronExpression,
        @Min(1) Integer fixedRateSeconds,
        Instant runAt,
        @Size(max = 64) String timezone,
        @Min(0) Integer maxRetries,
        @Min(1) Integer backoffInitialSecs,
        @DecimalMin("1.00") @Digits(integer = 2, fraction = 2) BigDecimal backoffMultiplier,
        @Min(1) Integer backoffMaxSecs,
        @Min(1) Integer timeoutSeconds,
        MisfirePolicy misfirePolicy,
        @Min(0) @Schema(description = "Required for PUT; use the version from the latest response. Omit for POST.") Long version) {

    public JobRequest {
        timezone = timezone == null ? "UTC" : timezone;
        maxRetries = maxRetries == null ? 3 : maxRetries;
        backoffInitialSecs = backoffInitialSecs == null ? 10 : backoffInitialSecs;
        backoffMultiplier = backoffMultiplier == null ? new BigDecimal("2.00") : backoffMultiplier;
        backoffMaxSecs = backoffMaxSecs == null ? 3600 : backoffMaxSecs;
        timeoutSeconds = timeoutSeconds == null ? 60 : timeoutSeconds;
        misfirePolicy = misfirePolicy == null ? MisfirePolicy.FIRE_ONCE_NOW : misfirePolicy;
    }
}
