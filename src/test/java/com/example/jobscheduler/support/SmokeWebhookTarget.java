package com.example.jobscheduler.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Test-only idempotent receiver: one logical effect per one-time job ID, attempts counted separately. */
public final class SmokeWebhookTarget {
    private SmokeWebhookTarget() {
    }

    public static void main(String[] args) throws IOException {
        var jobs = new ConcurrentHashMap<UUID, AtomicInteger>();
        var release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(executor);
        server.createContext("/health", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/release", exchange -> {
            release.countDown();
            respond(exchange, 200, "released");
        });
        server.createContext("/stats", exchange -> {
            var json = new StringBuilder("{\"jobs\":{");
            boolean first = true;
            for (var entry : jobs.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(entry.getKey()).append("\":{\"attempts\":")
                        .append(entry.getValue().get()).append(",\"effects\":1}");
            }
            respond(exchange, 200, json.append("}}").toString());
        });
        server.createContext("/", exchange -> {
            UUID job;
            try {
                job = UUID.fromString(exchange.getRequestHeaders().getFirst("X-Job-Id"));
            } catch (IllegalArgumentException | NullPointerException ex) {
                respond(exchange, 400, "X-Job-Id required");
                return;
            }
            var attempts = jobs.computeIfAbsent(job, id -> new AtomicInteger());
            int attempt = attempts.incrementAndGet();
            // The first effect is applied before acknowledging it. A killed worker cannot know that.
            if (exchange.getRequestURI().getPath().equals("/hold") && attempt == 1) {
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (exchange.getRequestURI().getPath().equals("/slow")) {
                try {
                    new CountDownLatch(1).await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, 200, "accepted");
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }));
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
