package com.example.jobscheduler.config;

import com.example.jobscheduler.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PayloadSizeValidator {
    private final ObjectMapper json;

    public PayloadSizeValidator(ObjectMapper json) {
        this.json = json;
    }

    public void validate(JsonNode payload) {
        try {
            if (payload == null || payload.isNull() || json.writeValueAsBytes(payload).length > 65536) {
                throw ApiException.invalid("payload", "Payload must contain at most 64 KB of UTF-8 JSON");
            }
        } catch (JsonProcessingException ex) {
            throw ApiException.invalid("payload", "Invalid JSON payload");
        }
    }
}
