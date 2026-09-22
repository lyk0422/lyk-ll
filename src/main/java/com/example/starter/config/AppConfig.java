package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置：提供可替换的时钟，测试中以可控时钟验证租约到期与时钟回拨。
 */
@Configuration
public class AppConfig {

    /**
     * 系统时钟（UTC），业务时间统一取 epoch 毫秒。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
