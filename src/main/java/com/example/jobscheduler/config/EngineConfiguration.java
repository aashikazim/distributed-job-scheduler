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
@EnableConfigurationProperties(SchedulerProperties.class)
public class EngineConfiguration {
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public SchedulerLoop schedulerLoop(JobClaimRepository claims, JobExecutionRepository executions,
                                       ExecutorRegistry executors, SchedulerProperties properties, Clock clock, Janitor janitor) {
        var node = NodeId.create();
        var runner = new JobRunner(executions, executors, properties, clock, node);
        return new SchedulerLoop(claims, executions, runner, properties, node, janitor);
    }
}
