package com.example.jobscheduler.config;

import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigurationTest {
    @Test
    void applicationClockUsesUtc() {
        assertThat(new TimeConfiguration().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
