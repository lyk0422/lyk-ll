package com.example.starter.config;

import com.example.starter.service.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 通用 Bean 配置。时间统一以 UTC 判定。
 */
@Configuration
public class AppConfig {

    @Bean
    public Clock systemClock() {
        return Clock.system(ZoneOffset.UTC);
    }

    @Bean
    public TimeProvider timeProvider(Clock clock) {
        return new TimeProvider(clock);
    }
}
