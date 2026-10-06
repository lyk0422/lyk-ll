package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置：提供可替换的时钟，便于时间相关规则的测试。
 */
@Configuration
public class AppConfig {

    /**
     * 系统默认时区时钟；测试中可覆盖为可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
