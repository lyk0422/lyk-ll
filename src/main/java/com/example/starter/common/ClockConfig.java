package com.example.starter.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 系统时钟配置：业务时间统一取自该 Bean（UTC），测试可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 生产默认使用 UTC 系统时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
