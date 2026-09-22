package com.example.starter.orchestrator;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executor;

/**
 * 确定性测试配置：提交即在调用线程内同步执行到终态；时钟固定。
 * 配合 application.yaml 中 orchestrator.plan-executor.async=false 使用。
 */
@TestConfiguration
public class SyncExecutorTestConfig {

    @Bean(name = "planExecutor")
    public Executor syncPlanExecutor() {
        return Runnable::run;
    }

    @Bean
    public Clock clock() {
        return Clock.fixed(Instant.parse("2026-09-22T08:00:00Z"), ZoneId.of("UTC"));
    }
}
