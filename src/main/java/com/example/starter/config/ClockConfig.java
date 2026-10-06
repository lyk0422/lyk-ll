package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间统一走 Clock，便于测试注入可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 系统默认时钟（Asia/Shanghai 由序列化层处理，业务比较使用 Instant/LocalDateTime）。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemDefaultZone();
    }
}
