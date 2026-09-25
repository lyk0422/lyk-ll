package com.example.starter;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 默认固定在 2026-09-25T10:00:00Z，用例内可再调整；同时满足 Clock 注入点。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));
    }
}
