package com.example.starter.lc.testsupport;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定时刻 2026-06-01T10:00:00Z 起步，测试中可注入并推进。
 */
@TestConfiguration
public class TestClockConfig {

    public static final MutableClock CLOCK =
            new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));

    @Bean
    @Primary
    public Clock testUtcClock() {
        return CLOCK;
    }
}
