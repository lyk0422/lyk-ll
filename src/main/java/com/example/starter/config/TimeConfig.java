package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源配置：业务统一使用 UTC 时钟，测试中可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
