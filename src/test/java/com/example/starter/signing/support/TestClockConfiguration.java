package com.example.starter.signing.support;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定基准时间的可变时钟替换系统时钟。
 * MutableClock 本身是 Clock 子类，作为 @Primary Clock 注入业务服务。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时间（UTC）。 */
    public static final Instant BASE_TIME = Instant.parse("2030-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME);
    }
}
