package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供可替换的 UTC 时钟，业务时限判定均基于该时钟，测试中可注入可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 生产默认使用 UTC 系统时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
