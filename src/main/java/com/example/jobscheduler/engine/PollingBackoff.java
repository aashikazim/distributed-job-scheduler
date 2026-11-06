package com.example.jobscheduler.engine;

import java.time.Duration;

/** Accessed only by the poll thread. Reset promptly when PostgreSQL becomes available. */
public final class PollingBackoff {
    private final long normalMillis;
    private int failures;

    public PollingBackoff(Duration interval) {
        normalMillis = interval.toMillis();
    }

    public long success() {
        failures = 0;
        return normalMillis;
    }

    public long failure() {
        failures = Math.min(20, failures + 1);
        return (long) Math.min(normalMillis * Math.pow(2, failures), Math.max(normalMillis, 30000));
    }
}
