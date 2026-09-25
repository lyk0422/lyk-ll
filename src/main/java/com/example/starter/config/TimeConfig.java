package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：统一使用 UTC 时钟，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟，所有 created_at 等服务器侧时间均取自该时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
