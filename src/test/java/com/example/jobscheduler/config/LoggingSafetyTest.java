package com.example.jobscheduler.config;

import java.util.Map;
import java.util.UUID;

import com.example.jobscheduler.api.JobRequest;
import com.example.jobscheduler.api.JobResponse;
import com.example.jobscheduler.domain.Job;
import com.example.jobscheduler.domain.ScheduleType;
import com.example.jobscheduler.executor.ExecutionContext;
import com.example.jobscheduler.util.LogContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.*;

class LoggingSafetyTest {
    @Test
    void dtoDiagnosticsDoNotExposePayloadSecretsButSerializationStillWorks() throws Exception {
        var json = new ObjectMapper();
        var request = json.readValue("""
                {"name":"sensitive","jobType":"HTTP","scheduleType":"FIXED_RATE","fixedRateSeconds":60,
                 "payload":{"url":"https://example.com","headers":{"Authorization":"secret-marker","X-API-Key":"secret-marker"}}}
                """, JobRequest.class);
        var response = JobResponse.from(new Job("sensitive", "HTTP", request.payload(), ScheduleType.FIXED_RATE));
        var context = new ExecutionContext(UUID.randomUUID(), UUID.randomUUID(), 1, request.payload(), java.time.Instant.EPOCH);
        assertThat(request.toString()).doesNotContain("secret-marker");
        assertThat(response.toString()).doesNotContain("secret-marker");
        assertThat(context.toString()).doesNotContain("secret-marker");
        var run = new com.example.jobscheduler.domain.JobRun(UUID.randomUUID(), 1,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, "node");
        run.setResultSummary("secret-marker");
        assertThat(com.example.jobscheduler.api.JobRunResponse.from(run).toString()).doesNotContain("secret-marker");
        assertThat(new com.example.jobscheduler.executor.ExecutionResult("secret-marker").toString()).doesNotContain("secret-marker");
        assertThat(json.writeValueAsString(request)).contains("secret-marker");
    }

    @Test
    void scopedMdcRestoresExistingContextAndClearsVirtualThreadContext() throws Exception {
        MDC.put("requestId", "previous");
        try {
            try (var ignored = new LogContext("node", UUID.randomUUID(), UUID.randomUUID())) {
                assertThat(MDC.getCopyOfContextMap()).containsKeys("jobId", "runId", "nodeId", "requestId");
            }
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(Map.of("requestId", "previous"));
        } finally {
            MDC.clear();
        }
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try (var ignored = new LogContext("virtual-node", UUID.randomUUID(), UUID.randomUUID())) {
                    assertThat(MDC.get("nodeId")).isEqualTo("virtual-node");
                }
                assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            }).get();
        }
    }
}
