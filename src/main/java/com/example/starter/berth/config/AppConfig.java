package com.example.starter.berth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 应用配置：提供 UTC 时钟（测试可替换为可控时钟）并启用定时任务。
 */
@Configuration
@EnableScheduling
public class AppConfig {

    /**
     * 业务时钟，统一使用 UTC。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
