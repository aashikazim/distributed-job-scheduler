package com.example.jobscheduler.engine;

import java.time.Duration;
import java.util.List;

import com.example.jobscheduler.config.SchedulerProperties;
import com.example.jobscheduler.repository.JobClaimRepository;
import com.example.jobscheduler.repository.JobExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PollingResilienceTest {
    @Test
    void pollingSurvivesDatabaseErrorsAndResumesAfterRecovery() {
        var claims = mock(JobClaimRepository.class);
        var executions = mock(JobExecutionRepository.class);
        var runner = mock(JobRunner.class);
        var janitor = mock(Janitor.class);
        when(claims.claim(anyString(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"))
                .thenReturn(List.of());
        var properties = new SchedulerProperties(Duration.ofMillis(20), 1, 1,
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofSeconds(30), 30, Duration.ofSeconds(60));
        try (var loop = new SchedulerLoop(claims, executions, runner, properties, "outage", janitor)) {
            loop.start();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    verify(claims, atLeast(4)).claim(eq("outage"), any(), eq(1)));
            assertThat(loop.isRunning()).isTrue();
            assertThat(loop.inFlight()).isZero();
        }
        verify(executions).release("outage");
        verify(runner).close();
    }

    @Test
    void shutdownToleratesDatabaseOutageAndIsIdempotent() {
        var executions = mock(JobExecutionRepository.class);
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(executions).release("offline");
        var properties = new SchedulerProperties(Duration.ofMillis(20), 1, 1,
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ZERO,
                Duration.ofSeconds(30), 30, Duration.ofSeconds(60));
        var loop = new SchedulerLoop(mock(JobClaimRepository.class), executions, mock(JobRunner.class), properties,
                "offline", mock(Janitor.class));
        org.assertj.core.api.Assertions.assertThatCode(() -> {
            loop.stop();
            loop.stop();
        }).doesNotThrowAnyException();
        verify(executions, times(1)).release("offline");
    }

    @Test
    void outageDelayDoublesCapsAndResetsAfterSuccess() {
        var backoff = new PollingBackoff(Duration.ofSeconds(2));
        assertThat(backoff.failure()).isEqualTo(4000);
        assertThat(backoff.failure()).isEqualTo(8000);
        for (int i = 0; i < 100; i++) {
            assertThat(backoff.failure()).isLessThanOrEqualTo(30000);
        }
        assertThat(backoff.success()).isEqualTo(2000);
        assertThat(backoff.failure()).isEqualTo(4000);
    }
}
