package com.example.starter;

import com.example.starter.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;

/**
 * 测试时钟配置：以可控时钟替换业务时钟，初始固定为 2026-09-25（周五）10:00 Asia/Shanghai。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 25, 10, 0);

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME.atZone(ClockConfig.BUSINESS_ZONE).toInstant(),
                ClockConfig.BUSINESS_ZONE);
    }
}
