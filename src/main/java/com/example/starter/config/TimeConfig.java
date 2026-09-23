package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置，集中提供 UTC 时钟，便于测试替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
