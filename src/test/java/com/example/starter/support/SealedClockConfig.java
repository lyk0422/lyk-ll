package com.example.starter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 密封报价测试时钟配置：以固定 UTC 时刻起步，测试内按需精确推进。
 */
@TestConfiguration
public class SealedClockConfig {

    public static final Instant START = Instant.parse("2026-09-27T00:00:00Z");

    @Bean
    @Primary
    public Clock sealedTestClock() {
        return new MutableClock(START);
    }
}
