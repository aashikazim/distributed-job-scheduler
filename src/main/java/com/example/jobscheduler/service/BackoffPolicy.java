package com.example.jobscheduler.service;

import java.util.concurrent.ThreadLocalRandom;

import com.example.jobscheduler.engine.ClaimedJob;
import org.springframework.stereotype.Component;

@Component
public class BackoffPolicy {
    public double seconds(ClaimedJob job) {
        return seconds(job.backoffInitialSecs(), job.backoffMultiplier().doubleValue(),
                job.retryCount(), job.backoffMaxSecs(), ThreadLocalRandom.current().nextDouble(-0.1, 0.1));
    }

    public double seconds(int initial, double multiplier, int retryCount, int maximum, double jitter) {
        return Math.min(initial * Math.pow(multiplier, retryCount), maximum) * (1 + jitter);
    }
}
