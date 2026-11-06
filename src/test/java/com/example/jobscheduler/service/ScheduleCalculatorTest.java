package com.example.jobscheduler.service;

import java.time.Instant;

import com.example.jobscheduler.api.ApiException;
import com.example.jobscheduler.domain.ScheduleType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleCalculatorTest {
    private final ScheduleCalculator calculator = new ScheduleCalculator();

    @Test
    void sixFieldCronUsesSecondsAndIsStrictlyAfterReference() {
        assertThat(cron("*/10 * * * * *", "UTC", "2026-01-01T00:00:10Z"))
                .isEqualTo(Instant.parse("2026-01-01T00:00:20Z"));
    }

    @Test
    void cronUsesJobTimezone() {
        assertThat(cron("0 0 2 * * *", "America/New_York", "2026-01-01T00:00:00Z"))
                .isEqualTo(Instant.parse("2026-01-01T07:00:00Z"));
    }

    @Test
    void springForwardSkipsNonexistentLocalTime() {
        assertThat(cron("0 30 2 * * *", "America/New_York", "2026-03-08T05:00:00Z"))
                .isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
    }

    @Test
    void fallBackIncludesBothOccurrencesOfRepeatedLocalTime() {
        var first = cron("0 30 1 * * *", "America/New_York", "2026-11-01T04:00:00Z");
        assertThat(first).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(calculator.next(ScheduleType.CRON, "0 30 1 * * *", null, null, "America/New_York", first))
                .isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
    }

    @Test
    void fixedRateStartsOneIntervalAfterReference() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(calculator.next(ScheduleType.FIXED_RATE, null, 60, null, "UTC", now))
                .isEqualTo(now.plusSeconds(60));
    }

    @Test
    void oneTimePreservesPastTimeSoWorkersCanApplyMisfirePolicy() {
        var past = Instant.parse("2025-01-01T00:00:00Z");
        assertThat(calculator.next(ScheduleType.ONE_TIME, null, null, past, "UTC", Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo(past);
    }

    @Test
    void invalidCronAndUnreachableDateAreRejected() {
        for (var expression : new String[]{"* * * * *", "@daily", "0 0 25 * * *", "0 0 0 31 2 *"}) {
            assertThatThrownBy(() -> cron(expression, "UTC", "2026-01-01T00:00:00Z"))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void offsetAndUnknownTimezoneAreRejected() {
        for (var zone : new String[]{"+02:00", "Mars/Olympus", ""}) {
            assertThatThrownBy(() -> cron("0 0 2 * * *", zone, "2026-01-01T00:00:00Z"))
                    .isInstanceOf(ApiException.class);
        }
    }

    private Instant cron(String expression, String zone, String reference) {
        return calculator.next(ScheduleType.CRON, expression, null, null, zone, Instant.parse(reference));
    }
}
