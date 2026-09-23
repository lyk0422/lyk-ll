package com.example.starter.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 审计平台基础 Bean：统一 UTC 时钟，测试中可替换为可控时钟。
 */
@Configuration
public class AuditConfig {

    @Bean
    public Clock auditClock() {
        return Clock.systemUTC();
    }
}
