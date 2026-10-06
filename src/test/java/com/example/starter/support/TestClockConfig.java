package com.example.starter.support;

import java.time.LocalDate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟覆盖主配置的 Clock Bean。
 */
@Configuration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(LocalDate.of(2026, 1, 5));
    }
}
