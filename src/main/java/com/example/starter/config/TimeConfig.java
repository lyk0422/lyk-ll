package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：业务时间统一取 UTC，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产环境使用 UTC 系统时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
