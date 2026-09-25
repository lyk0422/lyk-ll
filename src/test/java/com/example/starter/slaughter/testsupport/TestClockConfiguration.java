package com.example.starter.slaughter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：用可控的 {@link MutableClock} 替换生产 UTC 系统时钟。
 * MutableClock 本身即 Clock，作为 @Primary 候选注入所有 Clock 依赖。
 * 固定起点 2026-09-26T00:00:00Z，便于计算 6 小时恰满边界。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final Instant START = Instant.parse("2026-09-26T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
