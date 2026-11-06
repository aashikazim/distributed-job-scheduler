package com.example.jobscheduler.config;

import com.example.jobscheduler.api.JobController;
import com.example.jobscheduler.api.RunHistoryController;
import com.example.jobscheduler.engine.SchedulerLoop;
import com.example.jobscheduler.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"scheduler.role=WORKER", "scheduler.api-key="})
@AutoConfigureMockMvc
@AutoConfigureObservability
class WorkerRoleTest extends PostgresIntegrationTest {
    private final ApplicationContext context;
    private final MockMvc mvc;

    @Autowired
    WorkerRoleTest(ApplicationContext context, MockMvc mvc) {
        this.context = context;
        this.mvc = mvc;
    }

    @Test
    void workerRunsEngineWithoutApiKeyAndExposesOnlyActuator() throws Exception {
        assertThat(context.getBean(SchedulerLoop.class).isRunning()).isTrue();
        assertThat(context.getBeansOfType(JobController.class)).isEmpty();
        assertThat(context.getBeansOfType(RunHistoryController.class)).isEmpty();
        for (var path : new String[]{"/api/v1/jobs", "/swagger-ui.html", "/v3/api-docs"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
