package com.example.jobscheduler.executor;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.jobscheduler.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Validates configuration only; DNS checks and HTTP execution are added in later phases. */
@Component
public class HttpPayloadValidator {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESERVED_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");
    private final ObjectMapper mapper;

    public HttpPayloadValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void validate(String type, JsonNode payload) {
        if (!"HTTP".equals(type)) {
            throw ApiException.invalid("jobType", "Unknown job type; currently supported: HTTP");
        }
        if (payload == null || !payload.isObject()) {
            throw ApiException.invalid("payload", "HTTP payload must be a JSON object");
        }
        try {
            if (mapper.writeValueAsBytes(payload).length > 65536) {
                throw ApiException.invalid("payload", "Payload must not exceed 64 KB of UTF-8 JSON");
            }
        } catch (JsonProcessingException ex) {
            throw ApiException.invalid("payload", "Payload cannot be serialized");
        }
        var url = payload.path("url");
        try {
            if (!url.isTextual()) {
                throw new IllegalArgumentException();
            }
            var uri = URI.create(url.textValue());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalid("payload.url", "Use an absolute HTTP(S) URL without credentials or fragment");
        }
        if (payload.has("method") && (!payload.get("method").isTextual()
                || !METHODS.contains(payload.get("method").textValue()))) {
            throw ApiException.invalid("payload.method", "Unsupported HTTP method (use uppercase)");
        }
        if (payload.has("body") && !payload.get("body").isTextual()) {
            throw ApiException.invalid("payload.body", "Body must be a string");
        }
        if (payload.has("headers")) {
            var headers = payload.get("headers");
            if (!headers.isObject()) {
                throw ApiException.invalid("payload.headers", "Headers must be an object of string values");
            }
            headers.fields().forEachRemaining(header -> {
                var name = header.getKey();
                var lowerName = name.toLowerCase(Locale.ROOT);
                var value = header.getValue();
                if (!HEADER_NAME.matcher(name).matches() || RESERVED_HEADERS.contains(lowerName)
                        || lowerName.startsWith("x-job-") || !value.isTextual()
                        || value.textValue().chars().anyMatch(c -> c == 127 || (c < 32 && c != 9))) {
                    throw ApiException.invalid("payload.headers", "Invalid or reserved HTTP header");
                }
            });
        }
        if (payload.has("expectedStatus")) {
            var statuses = payload.get("expectedStatus");
            if (!statuses.isArray() || statuses.isEmpty()) {
                throw ApiException.invalid("payload.expectedStatus", "Expected status must be a nonempty array");
            }
            for (var status : statuses) {
                if (!status.isIntegralNumber() || !status.canConvertToInt() || status.intValue() < 100 || status.intValue() > 599) {
                    throw ApiException.invalid("payload.expectedStatus", "Expected status codes must be integers from 100 to 599");
                }
            }
        }
    }
}
