package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：统一通过 Clock 获取当前时间（UTC），便于 14 天监测期等时间规则的测试控制。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟；测试中可替换为可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
