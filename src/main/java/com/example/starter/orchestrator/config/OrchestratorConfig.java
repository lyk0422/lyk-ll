package com.example.starter.orchestrator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;

/**
 * 编排器基础 Bean：可控时钟与计划异步执行器（不编排真实进程，仅驱动内存状态机）。
 * 测试中设置 {@code orchestrator.plan-executor.async=false} 并提供同名同步执行器，
 * 可获得确定性执行。
 */
@Configuration
public class OrchestratorConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean("planExecutor")
    @ConditionalOnProperty(name = "orchestrator.plan-executor.async",
            havingValue = "true", matchIfMissing = true)
    public Executor planExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("plan-exec-");
        executor.initialize();
        return executor;
    }
}
