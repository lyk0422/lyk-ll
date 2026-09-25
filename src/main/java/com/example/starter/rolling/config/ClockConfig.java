package com.example.starter.rolling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 时钟配置：统一使用 Asia/Shanghai；测试可替换为固定时钟。
 */
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
