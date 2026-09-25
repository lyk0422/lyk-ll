package com.example.starter.blast.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：默认系统 UTC 时钟；测试可注入固定/偏移时钟以验证时段边界与并发时刻。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
