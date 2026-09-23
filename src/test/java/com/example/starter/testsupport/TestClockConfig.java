package com.example.starter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/** 测试内用 {@link MutableTestClock} 替换系统时钟。 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public Clock mutableClock() {
        return new MutableTestClock();
    }
}
