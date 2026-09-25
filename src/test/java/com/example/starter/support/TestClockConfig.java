package com.example.starter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定 UTC 时钟替换生产时钟，测试方法可随时推进时间。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时间：2026-03-10T12:00:00Z。 */
    public static final Instant BASE_TIME = Instant.parse("2026-03-10T12:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME);
    }
}
