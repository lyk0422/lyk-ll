package com.example.starter.blend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：固定业务时间，保证时间相关断言确定。
 */
@TestConfiguration
public class TestClockConfig {

    /** 固定业务时间：2026-09-25 18:00:00 Asia/Shanghai。 */
    public static final String FIXED_TIME_TEXT = "2026-09-25 18:00:00";

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
