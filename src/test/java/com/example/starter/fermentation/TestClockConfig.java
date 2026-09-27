package com.example.starter.fermentation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 测试专用固定时钟：时间相关判定（如 72 小时失效）以可控时刻复算。
 * 固定在 2026-09-28 12:00（Asia/Shanghai），业务时刻仍由请求参数显式传入。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试当前时刻。 */
    public static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 28, 12, 0);

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_NOW.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneId.of("Asia/Shanghai"));
    }
}
