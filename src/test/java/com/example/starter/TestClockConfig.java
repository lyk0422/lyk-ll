package com.example.starter;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换生产 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant START = Instant.parse("2026-09-25T08:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
