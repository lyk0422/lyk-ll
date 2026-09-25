package com.example.starter.forestry;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟覆盖系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableForestryClock() {
        return new MutableClock(LocalDate.of(2026, 3, 1));
    }
}
