package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟：固定为 2026-09-26（Asia/Shanghai），保证“当日”边界可控、测试可重复。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant FIXED_NOW = Instant.parse("2026-09-26T01:00:00Z");

    @Bean
    @Primary
    public Clock testClock() {
        return Clock.fixed(FIXED_NOW, ZoneId.of("Asia/Shanghai"));
    }
}
