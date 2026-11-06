package com.example.jobscheduler.executor;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record ExecutionContext(UUID jobId, UUID runId, int attempt, JsonNode payload, Instant deadline) {
    @Override
    public String toString() {
        return "ExecutionContext[jobId=" + jobId + ", runId=" + runId + ", attempt=" + attempt + ", payload=<redacted>]";
    }
}
