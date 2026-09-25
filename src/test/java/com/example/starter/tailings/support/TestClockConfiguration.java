package com.example.starter.tailings.support;

import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final Instant T0 = Instant.parse("2026-09-25T01:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0, ZoneId.of("Asia/Shanghai"));
    }
}
