package com.example.jobscheduler.util;

import java.util.UUID;

public final class NodeId {
    private NodeId() {
    }

    public static String create() {
        var hostname = System.getenv().getOrDefault("HOSTNAME", "local");
        return hostname.substring(0, Math.min(hostname.length(), 80)) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
