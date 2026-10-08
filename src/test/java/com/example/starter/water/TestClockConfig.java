package com.example.starter.water;

import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以 MutableClock 覆盖主 Clock Bean，基准时刻 2026-10-08 08:00 (Asia/Shanghai)。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 测试基准时刻：2026-10-08T08:00:00+08:00。
     */
    public static final Instant BASE_INSTANT = Instant.parse("2026-10-08T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_INSTANT, ZoneId.of("Asia/Shanghai"));
    }
}
