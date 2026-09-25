package com.example.starter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 测试时钟配置：以可控时钟替换系统时钟，初始时刻 2026-09-25T00:00:00Z。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant BASE = Instant.parse("2026-09-25T00:00:00Z");

    @Bean
    @Primary
    public Clock mutableClock() {
        return new MutableClock(BASE);
    }
}
