package com.example.jobscheduler.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;

/** Thread-local context is explicitly installed on poll, execution, and heartbeat threads. */
public final class LogContext implements AutoCloseable {
    private final Map<String, String> previous = MDC.getCopyOfContextMap();

    public LogContext(String node, UUID job, UUID run) {
        var context = previous == null ? new HashMap<String, String>() : new HashMap<>(previous);
        context.put("nodeId", node);
        if (job != null) {
            context.put("jobId", job.toString());
        }
        if (run != null) {
            context.put("runId", run.toString());
        }
        MDC.setContextMap(context);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
