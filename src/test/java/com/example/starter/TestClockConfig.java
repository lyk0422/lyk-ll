package com.example.starter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 可控时钟，初始为 2026-09-26T00:00:00Z。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-26T00:00:00Z"));
    }
}
