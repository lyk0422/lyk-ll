package com.example.starter.support;

import java.time.LocalDateTime;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用固定起点的可控时钟替换系统时钟（MutableClock 本身即 Clock）。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final LocalDateTime INITIAL_TIME = LocalDateTime.of(2026, 9, 25, 8, 0, 0);

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(INITIAL_TIME);
    }
}
