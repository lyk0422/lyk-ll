package com.example.starter.cutover;

import com.example.starter.cutover.support.TimeSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;

/**
 * 测试时钟配置：覆盖主配置的系统时钟，提供全局唯一可调时钟实例。
 */
@TestConfiguration
public class ClockTestConfiguration {

    public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 25, 10, 0, 0);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(BASE_TIME.atZone(TimeSource.ZONE).toInstant(), TimeSource.ZONE);
    }
}
