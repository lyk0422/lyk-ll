package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务时间统一从 Clock 获取，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 默认系统时钟（应用时区）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
