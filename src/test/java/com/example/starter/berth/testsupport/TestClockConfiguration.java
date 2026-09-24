package com.example.starter.berth.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：用固定起步的 {@link MutableClock} 替换生产 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时刻：2026-10-15 04:00 UTC，早于当日合成允许时段 06:00-12:00。 */
    public static final Instant BASE = Instant.parse("2026-10-15T04:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE);
    }
}
