package com.example.starter.irrigation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用固定 UTC 时钟：所有测试轮次均可复算，不依赖运行当天日期。
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockTestConfig {

    /** 固定时刻：2026-09-25T03:15:00Z。 */
    public static final Instant FIXED_INSTANT = Instant.parse("2026-09-25T03:15:00Z");

    /** 固定 UTC 时钟，覆盖生产系统时钟 Bean。 */
    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
