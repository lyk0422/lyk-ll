package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务时间统一取自 Clock，便于测试用可控时钟覆盖。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
