package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务统一通过注入的 Clock 取 UTC 时间，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 默认使用 UTC 系统时钟；90 日窗口与维保 15 日均按 UTC 日界计算。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
