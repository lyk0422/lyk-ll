package com.example.starter;

import com.example.starter.service.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 可控固定时钟：时间相关测试统一锚定在 2026-09-26 08:00:00（Asia/Shanghai）。
 */
@TestConfiguration
public class FixedClockConfig {

    public static final LocalDateTime FIXED = LocalDateTime.of(2026, 9, 26, 8, 0, 0);

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
    }

    public static Instant instant() {
        return FIXED.atZone(ClockConfig.ZONE).toInstant();
    }
}
