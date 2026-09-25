package com.example.starter.pension.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.starter.pension.support.UtcClock;

/**
 * 时间配置：默认系统 UTC 时钟；测试可提供固定时钟 Bean 覆盖。
 */
@Configuration
public class TimeConfig {

    @Bean
    public UtcClock utcClock() {
        return new UtcClock();
    }
}
