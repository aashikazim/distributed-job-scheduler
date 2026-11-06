package com.example.jobscheduler.config;

import java.time.Clock;

import com.example.jobscheduler.engine.JobRunner;
import com.example.jobscheduler.engine.Janitor;
import com.example.jobscheduler.engine.SchedulerLoop;
import com.example.jobscheduler.executor.ExecutorRegistry;
import com.example.jobscheduler.repository.JobClaimRepository;
import com.example.jobscheduler.repository.JobExecutionRepository;
import com.example.jobscheduler.util.NodeId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SchedulerProperties.class, AccessProperties.class, HttpProperties.class})
public class EngineConfiguration {
    @Bean
    public String schedulerNodeId() {
        return NodeId.create();
    }

    @Bean(destroyMethod = "")
    @org.springframework.context.annotation.Conditional(RoleConditions.Worker.class)
    @ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public SchedulerLoop schedulerLoop(JobClaimRepository claims, JobExecutionRepository executions,
                                       ExecutorRegistry executors, SchedulerProperties properties, Clock clock, Janitor janitor,
                                       @org.springframework.beans.factory.annotation.Qualifier("schedulerNodeId") String node, SchedulerMetrics metrics) {
        var runner = new JobRunner(executions, executors, properties, clock, node, metrics);
        return new SchedulerLoop(claims, executions, runner, properties, node, janitor, metrics);
    }
}
