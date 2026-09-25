package com.example.starter.config;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时钟配置：全部业务时间使用 UTC，测试可替换为固定时钟。
 */
@Configuration
public class TimeConfig {

    /** 业务时钟，默认系统 UTC 时钟。 */
    @Bean
    public Clock applicationClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
