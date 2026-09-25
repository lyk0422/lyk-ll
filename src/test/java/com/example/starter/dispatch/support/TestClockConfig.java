package com.example.starter.dispatch.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * 以可控时钟替换系统时钟，注入被测服务；Clock/MutableClock/RaceClock 共享同一实例。
 */
@TestConfiguration
public class TestClockConfig {

    private final RaceClock raceClock = new RaceClock();

    @Bean
    @Primary
    public Clock testClock() {
        return raceClock;
    }

    @Bean
    public MutableClock mutableClock() {
        return raceClock;
    }

    @Bean
    public RaceClock raceClock() {
        return raceClock;
    }
}
