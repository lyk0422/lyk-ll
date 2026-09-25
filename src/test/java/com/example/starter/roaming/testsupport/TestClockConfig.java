package com.example.starter.roaming.testsupport;

import com.example.starter.roaming.config.ClockConfig;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 固定基准时刻：2026-09-20T10:00:00Z。 */
    public static final Instant BASE = Instant.parse("2026-09-20T10:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE);
    }
}
