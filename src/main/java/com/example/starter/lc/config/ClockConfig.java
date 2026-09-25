package com.example.starter.lc.config;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时钟（UTC）。生产使用系统 UTC 时钟；测试可注入固定/可变时钟验证审单期边界。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock utcClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
