package com.example.starter.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：生产使用 UTC 系统时钟，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 提供 UTC 时钟，处罚送达、复检期限与结案时间均以此为准。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
