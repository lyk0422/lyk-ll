package com.example.starter.testsupport;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试装配：以固定初始时间的可控时钟替换系统时钟。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant INITIAL = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(INITIAL);
    }
}
