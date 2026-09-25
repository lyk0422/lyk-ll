package com.example.starter.cosmetic.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 测试时钟配置：以一个 {@link Primary} 的可变时钟覆盖生产系统时钟，
 * 注入点按类型 Clock 解析时优先选中该实例。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试固定初始时刻(UTC)。 */
    public static final Instant INITIAL_NOW = Instant.parse("2026-06-30T08:00:00Z");

    /** 可控可变时钟（同时是 Clock 类型的主候选）。 */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(INITIAL_NOW);
    }
}
