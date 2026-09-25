package com.example.starter.quarantine.testsupport;

import com.example.starter.quarantine.config.ClockConfig;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定 UTC 时刻替换系统时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时刻：2026-09-25T00:00:00Z。 */
    public static final Instant BASE = Instant.parse("2026-09-25T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE);
    }
}
