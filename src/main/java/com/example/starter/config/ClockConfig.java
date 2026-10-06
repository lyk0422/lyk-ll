package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间统一走 Clock，便于测试以可控时钟验证 14 天监测期等时间规则。
 */
@Configuration
public class ClockConfig {

    /**
     * 生产环境使用系统默认时区时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
