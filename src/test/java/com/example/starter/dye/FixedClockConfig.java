package com.example.starter.dye;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试固定时钟：2026-09-26 08:00:00 Asia/Shanghai，保证时间相关断言确定。
 */
@TestConfiguration
public class FixedClockConfig {

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
