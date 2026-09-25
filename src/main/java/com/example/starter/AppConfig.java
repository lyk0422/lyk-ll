package com.example.starter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 应用基础配置：业务时间统一走可替换的 UTC 时钟，测试可注入固定时钟。
 */
@Configuration
public class AppConfig {

    /** 业务时钟（UTC）。 */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
