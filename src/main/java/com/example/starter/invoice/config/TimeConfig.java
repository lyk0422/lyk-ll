package com.example.starter.invoice.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间相关配置：统一注入可控时钟，便于时间相关测试替换。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统默认时钟，时间相关测试可替换为固定时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
