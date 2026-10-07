package com.example.starter.rolling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 时钟配置：统一使用 UTC（返修放行流程要求全部时间按 UTC 记录）；测试可替换为固定时钟。
 */
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("UTC");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
