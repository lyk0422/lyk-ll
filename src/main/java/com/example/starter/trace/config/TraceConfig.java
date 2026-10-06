package com.example.starter.trace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 追溯服务基础配置。统一使用 Asia/Shanghai 时钟，测试可替换为可控时钟。
 */
@Configuration
public class TraceConfig {

    /**
     * 业务时钟，时间相关测试可覆盖为固定时钟。
     */
    @Bean
    public Clock traceClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
