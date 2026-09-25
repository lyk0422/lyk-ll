package com.example.starter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 测试时钟配置：以固定起点的 {@link MutableClock} 替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试统一时间起点：2026-09-01T00:00:00Z */
    public static final Instant TEST_EPOCH = Instant.parse("2026-09-01T00:00:00Z");

    @Bean
    @Primary
    public Clock mutableClock() {
        return new MutableClock(TEST_EPOCH);
    }
}
