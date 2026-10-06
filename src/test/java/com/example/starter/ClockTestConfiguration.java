package com.example.starter;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.starter.config.ClockConfig;

/**
 * 测试时钟配置：以可控时钟替换业务时钟。
 */
@TestConfiguration
public class ClockTestConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-06T02:00:00Z"), ClockConfig.ZONE);
    }
}
