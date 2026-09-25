package com.example.starter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 用可控时钟替换系统 UTC 时钟（MutableClock 本身即 Clock，主配置的 clock Bean 会退让）。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
