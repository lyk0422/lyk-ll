package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时钟（UTC）；测试可替换为固定时钟，业务时刻优先采用请求自带的作业时间。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
