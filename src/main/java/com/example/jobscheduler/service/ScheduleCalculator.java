package com.example.jobscheduler.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import com.example.jobscheduler.api.ApiException;
import com.example.jobscheduler.api.JobRequest;
import com.example.jobscheduler.domain.ScheduleType;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
public class ScheduleCalculator {
    private static final Set<String> TIMEZONES = ZoneId.getAvailableZoneIds();

    public void validate(JobRequest request) {
        zone(request.timezone());
        switch (request.scheduleType()) {
            case CRON -> {
                cron(request.cronExpression());
                if (request.fixedRateSeconds() != null || request.runAt() != null) {
                    throw ApiException.invalid("scheduleType", "CRON accepts only cronExpression");
                }
            }
            case FIXED_RATE -> {
                if (request.fixedRateSeconds() == null || request.fixedRateSeconds() < 1) {
                    throw ApiException.invalid("fixedRateSeconds", "A positive fixedRateSeconds is required");
                }
                if (request.cronExpression() != null || request.runAt() != null) {
                    throw ApiException.invalid("scheduleType", "FIXED_RATE accepts only fixedRateSeconds");
                }
            }
            case ONE_TIME -> {
                if (request.runAt() == null) {
                    throw ApiException.invalid("runAt", "runAt is required for ONE_TIME");
                }
                if (request.cronExpression() != null || request.fixedRateSeconds() != null) {
                    throw ApiException.invalid("scheduleType", "ONE_TIME accepts only runAt");
                }
            }
        }
    }

    /** Recurring slots are computed from the supplied reference time, with no catch-up backlog. */
    public Instant next(ScheduleType type, String expression, Integer rate, Instant runAt,
                        String timezone, Instant reference) {
        return switch (type) {
            case ONE_TIME -> runAt;
            case FIXED_RATE -> reference.plusSeconds(rate);
            case CRON -> {
                var next = cron(expression).next(reference.atZone(zone(timezone)));
                if (next == null) {
                    throw ApiException.invalid("cronExpression", "Cron expression has no future occurrence");
                }
                yield next.toInstant();
            }
        };
    }

    private CronExpression cron(String expression) {
        if (expression == null || expression.trim().split("\\s+").length != 6) {
            throw ApiException.invalid("cronExpression", "Cron must have six fields: sec min hour dom month dow");
        }
        try {
            return CronExpression.parse(expression);
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalid("cronExpression", "Invalid cron expression");
        }
    }

    private ZoneId zone(String timezone) {
        if (timezone == null || !TIMEZONES.contains(timezone)) {
            throw ApiException.invalid("timezone", "Use an IANA timezone such as UTC or America/New_York");
        }
        return ZoneId.of(timezone);
    }
}
