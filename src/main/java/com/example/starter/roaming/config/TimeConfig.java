package com.example.starter.roaming.config;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时钟配置：业务一律以 UTC 取时；测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock systemClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
