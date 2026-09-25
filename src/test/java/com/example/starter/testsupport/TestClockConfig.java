package com.example.starter.testsupport;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用固定 UTC 时间的可控时钟替换系统时钟。
 * 仅声明一个 bean（名为 mutableClock），以 {@link Primary} 优先于主配置的系统时钟，
 * 同时满足按 {@link Clock} 与按 {@link MutableClock} 类型的注入。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时间：2026-09-26T00:00:00Z。 */
    public static final Instant FIXED_NOW = Instant.parse("2026-09-26T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(FIXED_NOW);
    }
}
