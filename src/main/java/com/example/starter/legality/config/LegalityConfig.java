package com.example.starter.legality.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时钟配置；测试可替换为固定时钟，生产使用 UTC 系统时钟。
 */
@Configuration
public class LegalityConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
