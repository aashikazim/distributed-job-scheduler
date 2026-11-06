package com.example.jobscheduler.executor;

import com.example.jobscheduler.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpPayloadValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpPayloadValidator validator = new HttpPayloadValidator(mapper);

    @Test
    void acceptsExampleAndMinimalPayload() throws Exception {
        assertThatCode(() -> validator.validate("HTTP", mapper.readTree("""
                {"url":"https://example.com/hook","method":"POST","headers":{"Content-Type":"application/json"},
                 "body":"{}","expectedStatus":[200,202]}
                """))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("HTTP", mapper.readTree("{\"url\":\"https://example.com\"}")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "{\"url\":\"file:///etc/passwd\"}",
            "{\"url\":\"https://user:password@example.com\"}",
            "{\"url\":\"https://example.com:99999\"}",
            "{\"url\":\"https://example.com\",\"method\":\"INVALID\"}",
            "{\"url\":\"https://example.com\",\"body\":{}}",
            "{\"url\":\"https://example.com\",\"headers\":[]}",
            "{\"url\":\"https://example.com\",\"headers\":{\"Host\":\"evil\"}}",
            "{\"url\":\"https://example.com\",\"headers\":{\"X-Job-Id\":\"fake\"}}",
            "{\"url\":\"https://example.com\",\"headers\":{\"X-Test\":\"a\\r\\nb\"}}",
            "{\"url\":\"https://example.com\",\"expectedStatus\":[]}",
            "{\"url\":\"https://example.com\",\"expectedStatus\":[600]}",
            "{\"url\":\"https://example.com\",\"expectedStatus\":[200.5]}"
    })
    void rejectsInvalidConfiguration(String json) throws Exception {
        var payload = mapper.readTree(json);
        assertThatThrownBy(() -> validator.validate("HTTP", payload)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsUnknownExecutorAndOversizedUtf8Payload() {
        var payload = mapper.createObjectNode().put("url", "https://example.com");
        assertThatThrownBy(() -> validator.validate("SHELL", payload)).isInstanceOf(ApiException.class);
        payload.put("body", "é".repeat(33000));
        assertThatThrownBy(() -> validator.validate("HTTP", payload))
                .isInstanceOf(ApiException.class).hasMessageContaining("64 KB");
    }
}
