package com.example.jobscheduler.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

public final class RoleConditions {
    private RoleConditions() {
    }

    public static class Api extends AnyNestedCondition {
        public Api() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "scheduler.role", havingValue = "ALL", matchIfMissing = true)
        static class All { }

        @ConditionalOnProperty(name = "scheduler.role", havingValue = "API")
        static class ApiOnly { }
    }

    public static class Worker extends AnyNestedCondition {
        public Worker() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "scheduler.role", havingValue = "ALL", matchIfMissing = true)
        static class All { }

        @ConditionalOnProperty(name = "scheduler.role", havingValue = "WORKER")
        static class WorkerOnly { }
    }
}
