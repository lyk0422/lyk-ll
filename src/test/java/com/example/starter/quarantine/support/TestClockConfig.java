package com.example.starter.quarantine.support;

import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 测试时钟配置：仅在 test 激活时以可控时钟替换系统 UTC 时钟。
 */
@Configuration
@Profile("test")
public class TestClockConfig {

    /** 测试基准时刻（UTC）。 */
    public static final Instant BASE_INSTANT = Instant.parse("2026-09-25T08:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_INSTANT);
    }
}
