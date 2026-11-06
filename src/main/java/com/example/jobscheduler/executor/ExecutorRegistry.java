package com.example.jobscheduler.executor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.jobscheduler.api.ApiException;
import org.springframework.stereotype.Component;

@Component
public class ExecutorRegistry {
    private final Map<String, JobExecutor> executors;

    public ExecutorRegistry(List<JobExecutor> beans) {
        var registered = new HashMap<String, JobExecutor>();
        for (var executor : beans) {
            if (registered.putIfAbsent(executor.type(), executor) != null) {
                throw new IllegalStateException("Duplicate executor type: " + executor.type());
            }
        }
        executors = Map.copyOf(registered);
    }

    public java.util.Set<String> types() {
        return executors.keySet();
    }

    public JobExecutor get(String type) {
        var executor = executors.get(type);
        if (executor == null) {
            throw ApiException.invalid("jobType", "Unknown job type");
        }
        return executor;
    }
}
