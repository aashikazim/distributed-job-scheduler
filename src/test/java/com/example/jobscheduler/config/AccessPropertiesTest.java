package com.example.jobscheduler.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AccessPropertiesTest {
    @Test
    void missingKeyFailsClosedForApiRolesAndIsRedactedFromDiagnostics() {
        assertThatThrownBy(() -> new AccessProperties(AccessProperties.Role.API, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessProperties(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new AccessProperties(AccessProperties.Role.WORKER, null)).doesNotThrowAnyException();
        assertThat(new AccessProperties(AccessProperties.Role.ALL, "private-value").toString()).doesNotContain("private-value");
    }
}
