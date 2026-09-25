package com.example.starter.legality.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 时钟配置：生产使用系统 UTC 时钟；测试可通过 app.clock.fixed（ISO-8601）固定时钟。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.clock.fixed:}") String fixedInstant) {
        if (fixedInstant == null || fixedInstant.isBlank()) {
            return Clock.systemUTC();
        }
        return Clock.fixed(Instant.parse(fixedInstant), ZoneOffset.UTC);
    }
}
