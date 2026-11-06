package com.example.jobscheduler.executor;

public record ExecutionResult(String summary) {
    @Override
    public String toString() {
        return "ExecutionResult[summary=<redacted>]";
    }
}
