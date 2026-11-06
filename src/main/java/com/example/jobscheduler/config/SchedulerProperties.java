package com.example.jobscheduler.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("scheduler")
public record SchedulerProperties(Duration pollInterval, int batchSize, int maxConcurrentRuns,
                                  Duration leaseDuration, Duration heartbeatInterval, Duration shutdownGracePeriod) {
    public SchedulerProperties {
        if (pollInterval == null || pollInterval.toMillis() < 1 || batchSize < 1 || maxConcurrentRuns < 1
                || leaseDuration == null || leaseDuration.toMillis() < 3
                || heartbeatInterval == null || heartbeatInterval.toMillis() < 1
                || heartbeatInterval.compareTo(leaseDuration.dividedBy(3)) > 0
                || shutdownGracePeriod == null || shutdownGracePeriod.isNegative()) {
            throw new IllegalArgumentException("Invalid scheduler capacity, polling, lease, heartbeat, or shutdown settings");
        }
    }
}
