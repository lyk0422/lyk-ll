package com.example.starter.gas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试专用固定时钟：封存时刻等“当前时间”可预测（2026-09-26T12:00:00，Asia/Shanghai）。
 */
@TestConfiguration
public class FixedClockConfig {

    public static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 26, 12, 0, 0);

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.ofHours(8)), ZoneId.of("Asia/Shanghai"));
    }
}
