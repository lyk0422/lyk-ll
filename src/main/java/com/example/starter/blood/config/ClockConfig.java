package com.example.starter.blood.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间一律通过注入的 {@link Clock} 获取（UTC），
 * 测试可替换为可控时钟以覆盖效期边界。
 */
@Configuration
public class ClockConfig {

    /**
     * 生产默认使用 UTC 系统时钟。
     *
     * @return UTC 系统时钟
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
