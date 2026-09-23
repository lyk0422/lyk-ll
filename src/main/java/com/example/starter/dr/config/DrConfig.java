package com.example.starter.dr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 需求响应模块配置：暴露可控时钟，测试可替换为固定时钟。
 */
@Configuration
public class DrConfig {

    @Bean
    public Clock drClock() {
        return Clock.systemUTC();
    }
}
