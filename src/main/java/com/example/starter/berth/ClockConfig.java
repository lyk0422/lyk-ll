package com.example.starter.berth;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间统一走 Clock（UTC），测试可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
