package com.example.jobscheduler.executor;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.example.jobscheduler.util.TextLimit;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

@Component
public class HttpJobExecutor implements JobExecutor, DisposableBean {
    private final HttpPayloadValidator validator;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();

    public HttpJobExecutor(HttpPayloadValidator validator, Clock clock) {
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "HTTP";
    }

    @Override
    public void validatePayload(JsonNode payload) {
        validator.validate(type(), payload);
    }

    @Override
    public ExecutionResult execute(ExecutionContext context) throws Exception {
        var remaining = Duration.between(clock.instant(), context.deadline());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new TimeoutException("HTTP deadline exceeded");
        }
        var payload = context.payload();
        var request = HttpRequest.newBuilder(URI.create(payload.path("url").asText()))
                .timeout(remaining)
                .method(payload.path("method").asText("POST"), payload.has("body")
                        ? HttpRequest.BodyPublishers.ofString(payload.get("body").asText())
                        : HttpRequest.BodyPublishers.noBody());
        if (payload.has("headers")) {
            payload.get("headers").fields().forEachRemaining(header -> request.header(header.getKey(), header.getValue().asText()));
        }
        request.header("X-Job-Id", context.jobId().toString())
                .header("X-Job-Run-Id", context.runId().toString())
                .header("X-Job-Attempt", Integer.toString(context.attempt()));
        var pending = client.sendAsync(request.build(), response -> new LimitedBodySubscriber());
        try {
            // The future includes consuming the body, so slow streaming responses share the deadline.
            var response = pending.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            boolean expected = response.statusCode() >= 200 && response.statusCode() < 300;
            if (payload.has("expectedStatus")) {
                expected = false;
                for (var status : payload.get("expectedStatus")) {
                    expected |= status.intValue() == response.statusCode();
                }
            }
            if (!expected) {
                // Do not retain exception messages, URLs, headers, or failure bodies that might contain secrets.
                throw new UnexpectedHttpStatusException(response.statusCode());
            }
            return new ExecutionResult(TextLimit.truncate("HTTP " + response.statusCode() + "\n" + response.body()));
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        } finally {
            pending.cancel(true);
        }
    }

    @Override
    public void destroy() {
        client.shutdownNow();
    }

    public static class UnexpectedHttpStatusException extends Exception {
        public UnexpectedHttpStatusException(int status) {
            super("Unexpected HTTP status " + status);
        }
    }

    /** Consume the stream while retaining at most 4 KB, independent of the response size. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream(4096);
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                int length = Math.min(buffer.remaining(), 4096 - prefix.size());
                if (length > 0) {
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    prefix.writeBytes(bytes);
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(prefix.toString(StandardCharsets.UTF_8));
        }
    }
}
