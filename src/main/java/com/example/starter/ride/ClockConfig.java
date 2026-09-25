package com.example.starter.ride;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可控时钟配置：生产使用系统默认时区时钟，测试可替换为固定时钟以控制“当日”边界。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
