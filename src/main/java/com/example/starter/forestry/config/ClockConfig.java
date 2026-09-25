package com.example.starter.forestry.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务时钟配置：统一使用 UTC 日界，测试中可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 系统 UTC 时钟，所有自然日边界（90 日采伐期限、更新期限等）均以此为准。
     */
    @Bean
    public Clock forestryClock() {
        return Clock.systemUTC();
    }
}
