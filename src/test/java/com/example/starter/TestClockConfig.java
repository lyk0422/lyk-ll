package com.example.starter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统时钟，起点固定便于断言。
 */
@TestConfiguration
public class TestClockConfig {

    public static final long T0 = 1_700_000_000_000L;

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
