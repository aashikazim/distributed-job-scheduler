package com.example.jobscheduler.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {
    @Test
    void growsExponentiallyCapsAndAppliesJitter() {
        var policy = new BackoffPolicy();
        assertThat(policy.seconds(10, 2, 0, 3600, 0)).isEqualTo(10);
        assertThat(policy.seconds(10, 2, 2, 3600, 0)).isEqualTo(40);
        assertThat(policy.seconds(10, 2, 20, 3600, 0)).isEqualTo(3600);
        assertThat(policy.seconds(10, 2, 0, 3600, -0.1)).isEqualTo(9);
        assertThat(policy.seconds(10, 2, 0, 3600, 0.1)).isEqualTo(11);
    }
}
