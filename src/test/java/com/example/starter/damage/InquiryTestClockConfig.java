package com.example.starter.damage;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 询价流程测试配置：以固定 UTC 起点的可控时钟替换系统时钟（@Primary 按类型覆盖 ClockConfig）。
 */
@TestConfiguration
public class InquiryTestClockConfig {

    /** 固定基准时刻：2026-09-20T00:00:00Z */
    public static final Instant BASE = Instant.parse("2026-09-20T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableBusinessClock() {
        return new MutableClock(BASE);
    }
}
