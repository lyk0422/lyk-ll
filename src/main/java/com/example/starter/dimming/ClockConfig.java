package com.example.starter.dimming;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：默认从 2026-01-01T08:00:00+08:00 起步的可调时钟，
 * 保证合成演示与测试的时间确定性。
 */
@Configuration
public class ClockConfig {

    @Bean
    public AdjustableClock adjustableClock() {
        return new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    public Clock clock(AdjustableClock adjustableClock) {
        return adjustableClock;
    }
}
