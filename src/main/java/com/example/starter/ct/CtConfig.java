package com.example.starter.ct;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 证书透明日志通用配置。
 */
@Configuration
public class CtConfig {

    /**
     * 业务时钟，默认 UTC 系统时钟；测试可替换为固定时钟。
     */
    @Bean
    public Clock ctClock() {
        return Clock.systemUTC();
    }
}
