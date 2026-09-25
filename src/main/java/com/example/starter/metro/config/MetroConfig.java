package com.example.starter.metro.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间戳统一走 Clock，测试可替换为可控时钟。
 */
@Configuration
public class MetroConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
