package com.example.jobscheduler.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("scheduler.http")
public record HttpProperties(List<String> allowedHosts) {
    public HttpProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        if (allowedHosts.stream().anyMatch(host -> host.isBlank() || host.contains("*") || host.contains("/"))) {
            throw new IllegalArgumentException("HTTP allowed-hosts must contain explicit hosts, without wildcards or paths");
        }
    }
}
