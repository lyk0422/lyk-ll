package com.example.starter.orchestrator;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 编排器基础配置。时钟以 Bean 注入，测试可替换为可控时钟。
 */
@Configuration
public class OrchestratorConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
