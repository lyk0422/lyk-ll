package com.example.starter.portability;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以 @Primary 替换生产 UTC 时钟，默认固定在 2026-09-25T10:00:00Z。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
