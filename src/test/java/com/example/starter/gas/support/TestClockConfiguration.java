package com.example.starter.gas.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控的固定时钟（{@link MutableClock}，本身即 Clock）替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
