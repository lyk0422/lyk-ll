package com.example.starter.testsupport;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定起始时间的可变时钟替换系统时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    public Clock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
    }
}
