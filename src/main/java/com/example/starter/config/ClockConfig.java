package com.example.starter.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间统一走 Clock，便于测试使用可控时钟。
 */
@Configuration
public class ClockConfig {

    /** 业务时区，与 jackson 时区保持一致。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
