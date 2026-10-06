package com.example.starter;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时刻（UTC）。 */
    public static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
