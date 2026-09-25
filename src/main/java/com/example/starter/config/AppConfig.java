package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置：提供可替换的时钟，便于时间相关测试使用可控时钟。
 */
@Configuration
public class AppConfig {

    /**
     * 系统时钟（Asia/Shanghai），测试中可覆盖为固定时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
