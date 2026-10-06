package com.example.starter.pest;

import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控的 {@link MutableClock} 替换系统时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
