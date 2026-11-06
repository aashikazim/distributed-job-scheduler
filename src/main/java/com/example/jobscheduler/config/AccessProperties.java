package com.example.jobscheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("scheduler")
public record AccessProperties(Role role, String apiKey) {
    public enum Role { ALL, API, WORKER }

    public AccessProperties {
        role = role == null ? Role.ALL : role;
        if (role != Role.WORKER && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("SCHEDULER_API_KEY is required for API and ALL roles");
        }
    }

    @Override
    public String toString() {
        return "AccessProperties[role=" + role + ", apiKey=<redacted>]";
    }
}
