package com.example.starter.slot;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试时钟固定为 2026-06-01T00:00:00Z，保证发布时间断言确定。
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockTestConfig {

    public static final Instant FIXED = Instant.parse("2026-06-01T00:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED, ZoneOffset.UTC);
    }
}
