package com.example.starter.flood;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 集成测试时钟配置：以可控的固定时钟作为唯一 Clock Bean，
 * 既满足按 {@link Clock} 类型注入，也可在测试中按 {@link MutableClock} 类型取用推进时间。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    public static final Instant BASE_INSTANT = Instant.parse("2026-09-25T02:00:00Z");
    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Bean
    @Primary
    public MutableClock mutableFloodClock() {
        return new MutableClock(BASE_INSTANT, SHANGHAI);
    }
}
