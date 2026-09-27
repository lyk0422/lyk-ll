package com.example.starter.damage.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;

/**
 * 测试时钟配置：以主类型 {@link MutableUtcClock} 替换系统 UTC 时钟，
 * 业务代码仍按 Clock 类型注入，测试可显式拨针验证截止时刻边界。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableUtcClock mutableBusinessClock() {
        return MutableUtcClock.startAt(LocalDateTime.of(2026, 10, 1, 0, 0, 0));
    }
}
