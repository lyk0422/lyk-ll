package com.example.starter.schedule.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定时刻的 {@link MutableClock} 覆盖生产系统时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
    }
}
