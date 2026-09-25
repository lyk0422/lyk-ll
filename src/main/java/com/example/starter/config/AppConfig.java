package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置：提供可替换的 UTC 时钟，测试以可控时钟覆盖。
 */
@Configuration
public class AppConfig {

    /**
     * 业务时钟，统一使用 UTC；操作票 24 小时有效期以此计算。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
