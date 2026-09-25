package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用基础配置：统一注入 UTC 时钟，业务时间判断全部基于该时钟，测试中可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 系统默认 UTC 时钟；所有“当前时刻”均从该 Bean 获取，单元测试可覆盖为固定/可调时钟。
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
