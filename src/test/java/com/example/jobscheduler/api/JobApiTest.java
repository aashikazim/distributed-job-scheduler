package com.example.jobscheduler.api;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.example.jobscheduler.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"scheduler.enabled=false", "scheduler.api-key=test-only-key",
        "scheduler.http.allowed-hosts=example.com,127.0.0.1,localhost"})
@AutoConfigureMockMvc
@Import(JobApiTest.FixedTime.class)
class JobApiTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private MockMvc mvc;
    private final org.springframework.web.context.WebApplicationContext context;
    private final com.example.jobscheduler.config.ApiAccessFilter filter;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    @Autowired
    JobApiTest(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc,
               org.springframework.web.context.WebApplicationContext context,
               com.example.jobscheduler.config.ApiAccessFilter filter) {
        this.mvc = mvc;
        this.context = context;
        this.filter = filter;
        this.json = json;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void cleanDatabase() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").header("X-API-Key", "test-only-key"))
                .addFilters(filter).build();
        jdbc.execute("TRUNCATE jobs CASCADE");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Test
    void createGetAndDelete() throws Exception {
        var job = create(definition("report"));
        assertThat(job.path("nextRunAt").asText()).isEqualTo("2026-01-01T00:01:00Z");
        assertThat(job.path("timezone").asText()).isEqualTo("UTC");
        assertThat(job.path("version").asLong()).isZero();
        assertThat(job.path("maxRetries").asInt()).isEqualTo(3);
        assertThat(job.has("lockedBy")).isFalse();
        mvc.perform(get(path(job))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("report"));
        mvc.perform(delete(path(job))).andExpect(status().isNoContent());
        mvc.perform(get(path(job))).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void pauseIsNoOpWhenAlreadyPausedAndResumeRecomputesSchedule() throws Exception {
        var job = create(definition("pause"));
        var paused = action(job, "pause");
        assertThat(paused.path("status").asText()).isEqualTo("PAUSED");
        var again = action(job, "pause");
        assertThat(again.path("version")).isEqualTo(paused.path("version"));
        jdbc.update("UPDATE jobs SET next_run_at = now() - interval '1 day' WHERE id = ?", UUID.fromString(job.path("id").asText()));
        var resumed = action(job, "resume");
        assertThat(resumed.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(resumed.path("nextRunAt").asText()).isEqualTo("2026-01-01T00:01:00Z");
    }

    @Test
    void triggerActivatesPausedJobAndUsesDatabaseClockWithoutExecuting() throws Exception {
        var job = create(definition("trigger"));
        action(job, "pause");
        var before = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        var triggered = action(job, "trigger");
        var after = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        assertThat(Instant.parse(triggered.path("nextRunAt").asText())).isBetween(before, after);
        assertThat(triggered.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Long.class)).isZero();
    }

    @Test
    void updatePreservesDueTimeUntilScheduleChangesAndRejectsStaleVersion() throws Exception {
        var job = create(definition("update"));
        var request = definition("renamed").put("version", job.path("version").asLong());
        var edited = update(job, request);
        assertThat(edited.path("name").asText()).isEqualTo("renamed");
        assertThat(edited.path("nextRunAt")).isEqualTo(job.path("nextRunAt"));
        mvc.perform(put(path(job)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isConflict());
        request.put("version", edited.path("version").asLong()).put("fixedRateSeconds", 120);
        var rescheduled = update(job, request);
        assertThat(rescheduled.path("nextRunAt").asText()).isEqualTo("2026-01-01T00:02:00Z");
    }

    @Test
    void updateRequiresVersionAndRejectsDuplicateNames() throws Exception {
        var a = create(definition("a"));
        var b = create(definition("b"));
        mvc.perform(put(path(a)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(definition("a"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.version").exists());
        mvc.perform(put(path(a)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(definition("b").put("version", a.path("version").asLong()))))
                .andExpect(status().isConflict());
        mvc.perform(get(path(b))).andExpect(status().isOk());
    }

    @Test
    void duplicateCreateReturnsConflict() throws Exception {
        create(definition("unique"));
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(definition("unique"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void listsPagesAndCombinesFiltersWithLiteralSubstringSearch() throws Exception {
        create(definition("report_a"));
        create(definition("report_b"));
        var paused = create(definition("report_c"));
        action(paused, "pause");
        mvc.perform(get("/api/v1/jobs").param("size", "1").param("page", "1")
                        .param("status", "ACTIVE").param("type", "HTTP").param("name", "REPORT_"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/v1/jobs").param("name", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/jobs").param("type", "UNKNOWN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void validatesBodyAndPaginationWithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.payload").exists()).andExpect(jsonPath("$.fields.scheduleType").exists());
        for (var parameter : new String[]{"page", "size"}) {
            mvc.perform(get("/api/v1/jobs").param(parameter, "-1"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields." + parameter).exists());
        }
        mvc.perform(get("/api/v1/jobs").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(definition("bad").put("timeoutSeconds", 0))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.timeoutSeconds").exists());
    }

    @Test
    void rejectsInvalidCronTimezoneScheduleAndJobType() throws Exception {
        var badCron = definition("bad").put("scheduleType", "CRON").put("cronExpression", "* * * * *");
        badCron.remove("fixedRateSeconds");
        assertInvalid(badCron, "cronExpression");
        assertInvalid(definition("bad").put("timezone", "Mars/Olympus"), "timezone");
        assertInvalid(definition("bad").put("jobType", "SHELL"), "jobType");
        assertInvalid(definition("bad").put("scheduleType", "ONE_TIME"), "runAt");
        assertInvalid(definition("bad").put("cronExpression", "0 * * * * *"), "scheduleType");
        var badPayload = definition("bad");
        ((ObjectNode) badPayload.path("payload")).put("url", "file:///etc/passwd");
        assertInvalid(badPayload, "payload.url");
    }

    @Test
    void oneTimeAndTimezoneCronComputeInitialDueTime() throws Exception {
        var once = definition("once").put("scheduleType", "ONE_TIME").put("runAt", "2025-01-01T00:00:00Z");
        once.remove("fixedRateSeconds");
        assertThat(create(once).path("nextRunAt").asText()).isEqualTo("2025-01-01T00:00:00Z");
        var cron = definition("cron").put("scheduleType", "CRON").put("cronExpression", "0 0 2 * * *")
                .put("timezone", "America/New_York");
        cron.remove("fixedRateSeconds");
        assertThat(create(cron).path("nextRunAt").asText()).isEqualTo("2026-01-01T07:00:00Z");
    }

    @Test
    void unknownIdsAndMalformedRequestsUseProblemDetails() throws Exception {
        var path = "/api/v1/jobs/" + UUID.randomUUID();
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(delete(path)).andExpect(status().isNotFound());
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(definition("missing").put("version", 0))))
                .andExpect(status().isNotFound());
        for (var action : new String[]{"pause", "resume", "trigger"}) {
            mvc.perform(post(path + "/" + action)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/jobs/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/v1/jobs").param("status", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void openApiAndSwaggerUiAreServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/jobs']").exists());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void historyEndpointsReturnRecordsAndValidatePaginationAndIds() throws Exception {
        var job = create(definition("history-api"));
        var jobId = UUID.fromString(job.path("id").asText());
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_runs (id, job_id, attempt, status, scheduled_for, started_at, finished_at, node_id, result_summary)
                VALUES (?, ?, 1, 'SUCCEEDED', now(), now(), now(), 'test-node', 'HTTP 200')
                """, runId, jobId);
        mvc.perform(get(path(job) + "/runs").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(runId.toString()));
        mvc.perform(get("/api/v1/runs/" + runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(get("/api/v1/runs/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/" + UUID.randomUUID() + "/runs")).andExpect(status().isNotFound());
        mvc.perform(get(path(job) + "/runs").param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    void liveLeaseRejectsMutationButAllowsPause() throws Exception {
        var job = create(definition("leased"));
        jdbc.update("UPDATE jobs SET locked_by = 'worker', locked_until = now() + interval '1 minute', version = version + 1 WHERE id = ?",
                UUID.fromString(job.path("id").asText()));
        for (var action : new String[]{"resume", "trigger"}) {
            mvc.perform(post(path(job) + "/" + action)).andExpect(status().isConflict());
        }
        mvc.perform(put(path(job)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(definition("leased").put("version", 1))))
                .andExpect(status().isConflict());
        mvc.perform(delete(path(job))).andExpect(status().isConflict());
        mvc.perform(post(path(job) + "/pause")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    private ObjectNode definition(String name) {
        var request = json.createObjectNode().put("name", name).put("jobType", "HTTP")
                .put("scheduleType", "FIXED_RATE").put("fixedRateSeconds", 60);
        request.putObject("payload").put("url", "https://example.com/hook").put("method", "POST");
        return request;
    }

    private JsonNode create(ObjectNode request) throws Exception {
        var result = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode update(JsonNode job, ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(put(path(job)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode action(JsonNode job, String action) throws Exception {
        return json.readTree(mvc.perform(post(path(job) + "/" + action)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void assertInvalid(ObjectNode request, String field) throws Exception {
        var result = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("fields").has(field)).isTrue();
    }

    private String path(JsonNode job) {
        return "/api/v1/jobs/" + job.path("id").asText();
    }
}
