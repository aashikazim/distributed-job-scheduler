package com.example.jobscheduler.executor;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.example.jobscheduler.util.TextLimit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

class HttpJobExecutorTest {
    @RegisterExtension
    static WireMockExtension http = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsPayloadHeadersAndExecutionIdentity() throws Exception {
        http.stubFor(post("/hook").willReturn(aResponse().withStatus(202).withBody("accepted")));
        try (var resource = executor()) {
            var payload = json.createObjectNode().put("url", http.baseUrl() + "/hook")
                    .put("method", "POST").put("body", "{\"report\":true}");
            payload.putObject("headers").put("Content-Type", "application/json");
            payload.putArray("expectedStatus").add(200).add(202);
            var context = new ExecutionContext(UUID.randomUUID(), UUID.randomUUID(), 2, payload, Instant.now().plusSeconds(5));
            var result = resource.value.execute(context);
            assertThat(result.summary()).isEqualTo("HTTP 202\naccepted");
            http.verify(postRequestedFor(urlEqualTo("/hook"))
                    .withHeader("X-Job-Id", equalTo(context.jobId().toString()))
                    .withHeader("X-Job-Run-Id", equalTo(context.runId().toString()))
                    .withHeader("X-Job-Attempt", equalTo("2"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalTo("{\"report\":true}")));
        }
    }

    @Test
    void rejectsUnexpectedStatusWithoutRetainingFailureBody() {
        http.stubFor(get("/bad").willReturn(aResponse().withStatus(503).withBody("secret-response")));
        try (var resource = executor()) {
            assertThatThrownBy(() -> resource.value.execute(context("/bad", 5)))
                    .isInstanceOf(HttpJobExecutor.UnexpectedHttpStatusException.class)
                    .hasMessage("Unexpected HTTP status 503");
        }
    }

    @Test
    void timesOutSlowResponseAndRejectsExpiredDeadline() {
        http.stubFor(get("/slow").willReturn(aResponse().withFixedDelay(2000).withStatus(200)));
        try (var resource = executor()) {
            assertThatThrownBy(() -> resource.value.execute(context("/slow", 0.1)))
                    .isInstanceOfAny(TimeoutException.class, java.net.http.HttpTimeoutException.class);
            assertThatThrownBy(() -> resource.value.execute(context("/slow", -1)))
                    .isInstanceOf(TimeoutException.class);
        }
    }

    @Test
    void truncatesUnicodeBodiesByUtf8BytesAndDoesNotFollowRedirects() throws Exception {
        http.stubFor(get("/large").willReturn(ok("é".repeat(10000))));
        http.stubFor(get("/redirect").willReturn(aResponse().withStatus(302).withHeader("Location", "/target")));
        http.stubFor(get("/target").willReturn(ok("should not run")));
        try (var resource = executor()) {
            assertThat(resource.value.execute(context("/large", 5)).summary().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(4096);
            assertThatThrownBy(() -> resource.value.execute(context("/redirect", 5)))
                    .isInstanceOf(HttpJobExecutor.UnexpectedHttpStatusException.class);
            http.verify(0, getRequestedFor(urlEqualTo("/target")));
        }
        assertThat(TextLimit.truncate("a".repeat(4095) + "é")).hasSize(4095);
        assertThat(TextLimit.truncate("hello\u0000world")).isEqualTo("helloworld");
    }

    @Test
    void defaultGuardBlocksLoopbackBeforeAnyRequestIsSent() {
        http.stubFor(get("/blocked").willReturn(ok()));
        var guarded = new HttpJobExecutor(new HttpPayloadValidator(json), Clock.systemUTC(),
                new SsrfGuard(new com.example.jobscheduler.config.HttpProperties(java.util.List.of())));
        try {
            assertThatThrownBy(() -> guarded.validatePayload(context("/blocked", 5).payload()))
                    .isInstanceOf(com.example.jobscheduler.api.ApiException.class);
            assertThatThrownBy(() -> guarded.execute(context("/blocked", 5)))
                    .isInstanceOf(com.example.jobscheduler.api.ApiException.class);
            http.verify(0, getRequestedFor(urlEqualTo("/blocked")));
        } finally {
            guarded.destroy();
        }
    }

    private ExecutionContext context(String path, double seconds) {
        var payload = json.createObjectNode().put("url", http.baseUrl() + path).put("method", "GET");
        return new ExecutionContext(UUID.randomUUID(), UUID.randomUUID(), 1, payload, Instant.now().plusMillis((long) (seconds * 1000)));
    }

    private ExecutorResource executor() {
        return new ExecutorResource(new HttpJobExecutor(new HttpPayloadValidator(json), Clock.systemUTC(),
                new SsrfGuard(new com.example.jobscheduler.config.HttpProperties(java.util.List.of("localhost", "127.0.0.1")))));
    }

    private record ExecutorResource(HttpJobExecutor value) implements AutoCloseable {
        @Override
        public void close() {
            value.destroy();
        }
    }
}
