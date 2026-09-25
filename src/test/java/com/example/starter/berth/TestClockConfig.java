package com.example.starter.berth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定时刻 2026-10-01T00:00:00Z 启动的可控时钟，
 * 覆盖主配置中的系统时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时刻 */
    public static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE);
    }
}
