package com.example.jobscheduler.config;

import com.example.jobscheduler.util.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** API pods refresh observability data without claiming/executing jobs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scheduler.role", havingValue = "API")
@EnableScheduling
public class ApiMetricsConfiguration {
    @Bean
    public BacklogRefresh apiBacklogRefresh(SchedulerMetrics metrics, @Qualifier("schedulerNodeId") String node) {
        return new BacklogRefresh(metrics, node);
    }

    public static class BacklogRefresh {
        private static final Logger log = LoggerFactory.getLogger(BacklogRefresh.class);
        private final SchedulerMetrics metrics;
        private final String node;

        BacklogRefresh(SchedulerMetrics metrics, String node) {
            this.metrics = metrics;
            this.node = node;
        }

        @Scheduled(fixedDelayString = "${scheduler.poll-interval:2s}")
        public void refresh() {
            try (var context = new LogContext(node, null, null)) {
                metrics.refreshBacklog();
            } catch (Exception ex) {
                try (var context = new LogContext(node, null, null)) {
                    log.warn("Backlog refresh failed ({})", ex.getClass().getSimpleName());
                }
            }
        }
    }
}
