package com.example.starter.water;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试用固定时钟：时间相关断言基于确定时刻，业务月份来自请求参数不受影响。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant FIXED_NOW = Instant.parse("2026-06-15T08:30:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
}
