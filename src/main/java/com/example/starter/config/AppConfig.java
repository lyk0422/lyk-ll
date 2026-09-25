package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置：提供 UTC 时钟，业务时间统一取自该 Bean，测试可替换为可控时钟。
 */
@Configuration
public class AppConfig {

    /**
     * 系统 UTC 时钟；宰前检疫 6 小时有效期等时间判断均基于该时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
