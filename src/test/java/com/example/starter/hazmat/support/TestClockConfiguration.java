package com.example.starter.hazmat.support;

import com.example.starter.hazmat.config.TimeConfig;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统时钟，初始时刻 2026-06-01T00:00:00Z。
 */
@Configuration
public class TestClockConfiguration {

    public static final Instant BASE_INSTANT = Instant.parse("2026-06-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_INSTANT, TimeConfig.APP_ZONE);
    }
}
