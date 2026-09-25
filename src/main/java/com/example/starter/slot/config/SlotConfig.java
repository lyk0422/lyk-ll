package com.example.starter.slot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时刻协调服务基础配置；时钟以 Bean 暴露，便于测试注入固定时钟。
 */
@Configuration
public class SlotConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
