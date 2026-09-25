package com.example.starter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定 UTC 时刻启动并可在测试中推进，替换生产系统时钟。
 * MutableClock 本身即 {@link java.time.Clock}，全容器仅一个 Clock Bean。
 */
@SuppressWarnings("unused")
@TestConfiguration
public class TestClockConfig {

    public static final Instant BASE = Instant.parse("2026-09-20T08:00:00Z");

    @Bean
    @Primary
    public MutableClock clock() {
        return new MutableClock(BASE);
    }
}
