package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：业务时间统一走 Clock（UTC），测试可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 生产环境使用 UTC 系统时钟，公示期与补正时限均按 UTC 日界计算。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
