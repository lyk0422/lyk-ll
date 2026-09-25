package com.example.starter.weight.support;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 固定起始时刻（UTC）：2026-09-01T00:00:00Z。
     */
    public static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START);
    }

    @Bean
    @Primary
    public Clock clock(MutableClock mutableClock) {
        return mutableClock;
    }
}
