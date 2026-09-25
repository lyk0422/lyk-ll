package com.example.starter.roaming.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时钟配置，业务时间均以 UTC 为准；测试可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
