package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置：提供 UTC 时钟，便于业务与测试使用可控时间。
 */
@Configuration
public class AppConfig {

    /**
     * 业务时钟，默认 UTC 系统时钟；测试中可替换为可控时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
