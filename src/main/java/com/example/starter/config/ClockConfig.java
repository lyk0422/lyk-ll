package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全局时钟配置：业务时间统一取 UTC，测试可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 系统 UTC 时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
