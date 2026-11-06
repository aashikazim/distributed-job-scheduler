package com.example.jobscheduler.config;

import com.example.jobscheduler.engine.SchedulerLoop;
import com.example.jobscheduler.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"scheduler.role=API", "scheduler.api-key=security-test-key", "scheduler.http.allowed-hosts=example.com"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class SecurityIntegrationTest extends PostgresIntegrationTest {
    private final MockMvc mvc;
    private final ConfigurableApplicationContext context;
    private final HealthContributorRegistry health;
    private final JdbcTemplate jdbc;
    private final SchedulerMetrics metrics;

    @Autowired
    SecurityIntegrationTest(MockMvc mvc, ConfigurableApplicationContext context, HealthContributorRegistry health,
                            JdbcTemplate jdbc, SchedulerMetrics metrics) {
        this.mvc = mvc;
        this.context = context;
        this.health = health;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Test
    void apiRoleHasNoWorkerAndRequiresExactlyOneValidKey() throws Exception {
        assertThat(context.getBeansOfType(SchedulerLoop.class)).isEmpty();
        mvc.perform(get("/api/v1/jobs")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/jobs").header("X-API-Key", "wrong-secret")).andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wrong-secret"))));
        mvc.perform(get("/api/v1/jobs").header("X-API-Key", "security-test-key", "security-test-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs").header("X-API-Key", "security-test-key")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.ApiKey.name").value("X-API-Key"));
    }

    @Test
    void publicRouteAliasesCannotBypassAuthentication() throws Exception {
        for (var path : new String[]{"/swagger-ui/../api/v1/jobs", "/actuator/../api/v1/jobs",
                "/actuator/%2e%2e/api/v1/jobs", "/swagger-ui/./../api/v1/jobs", "/api//v1/jobs",
                "/actuator/health;ignored/../../api/v1/jobs"}) {
            // MockMvc otherwise normalizes some aliases before the filter sees them.
            mvc.perform(get("/ignored").with(request -> {
                request.setRequestURI(path);
                return request;
            })).andExpect(status().isBadRequest());
        }
    }

    @Test
    void privateTargetsAndOversizedPayloadOrRequestAreRejected() throws Exception {
        String template = """
                {"name":"security-check","jobType":"HTTP","scheduleType":"FIXED_RATE","fixedRateSeconds":60,
                 "payload":{"url":"%s","body":"%s"}}
                """;
        mvc.perform(post("/api/v1/jobs").header("X-API-Key", "security-test-key").contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted("http://169.254.169.254/", "")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fields['payload.url']").exists());
        mvc.perform(post("/api/v1/jobs").header("X-API-Key", "security-test-key").contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted("https://example.com", "x".repeat(66000))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fields.payload").exists());
        mvc.perform(post("/api/v1/jobs").header("X-API-Key", "security-test-key").contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(ApiAccessFilter.MAX_REQUEST_BYTES + 1)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void databaseFailureAffectsReadinessButNotLiveness() throws Exception {
        var original = health.unregisterContributor("db");
        health.registerContributor("db", (HealthIndicator) () -> Health.down().build());
        try {
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        } finally {
            health.unregisterContributor("db");
            health.registerContributor("db", original);
        }
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void prometheusContainsEveryRequiredMetricAndBacklogUsesDatabaseClock() throws Exception {
        jdbc.execute("TRUNCATE jobs CASCADE");
        jdbc.update("""
                INSERT INTO jobs (id, name, job_type, payload, schedule_type, status, next_run_at)
                SELECT gen_random_uuid(), 'backlog-' || n, 'HTTP', '{}', 'ONE_TIME', 'ACTIVE', now() - interval '10 seconds'
                FROM generate_series(1, 2) n
                """);
        metrics.refreshBacklog();
        var registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        assertThat(registry.get("scheduler_jobs_due").gauge().value()).isEqualTo(2);
        assertThat(registry.get("scheduler_lag_seconds").gauge().value()).isGreaterThanOrEqualTo(10);
        var result = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var metric : new String[]{"scheduler_jobs_due", "scheduler_lag_seconds", "scheduler_running",
                "scheduler_claim_duration_seconds_count", "scheduler_claimed_total", "scheduler_run_duration_seconds_count",
                "scheduler_runs_total", "scheduler_dead_letter_total"}) {
            assertThat(result).contains(metric);
        }
    }
}
