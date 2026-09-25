package com.example.starter.water.testsupport;

import java.time.Instant;

import com.example.starter.water.support.TimeProvider;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用固定可控时钟替换系统时钟。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public TimeProvider mutableTimeProvider() {
        return new MutableTimeProvider(Instant.parse("2026-06-01T00:00:00Z"));
    }
}
