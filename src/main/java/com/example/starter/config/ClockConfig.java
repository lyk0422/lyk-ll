package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：默认系统 UTC 时钟；测试中可注入可控时钟验证第 15 日边界。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
