package com.example.jobscheduler.executor;

import com.fasterxml.jackson.databind.JsonNode;

public interface JobExecutor {
    String type();
    void validatePayload(JsonNode payload);
    ExecutionResult execute(ExecutionContext context) throws Exception;
}
