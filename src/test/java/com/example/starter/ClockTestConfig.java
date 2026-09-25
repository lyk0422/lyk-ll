package com.example.starter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以可控的固定 UTC 时钟替换系统时钟。
 * MutableClock 本身是 Clock，故只注册一个 Bean，避免按类型注入歧义。
 */
@TestConfiguration
public class ClockTestConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));
    }
}
