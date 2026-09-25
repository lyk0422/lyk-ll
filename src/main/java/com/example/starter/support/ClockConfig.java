package com.example.starter.support;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间统一取自 Clock，测试可替换为固定时钟做时间相关断言。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemDefaultZone();
    }
}
