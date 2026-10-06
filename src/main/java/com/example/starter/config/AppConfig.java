package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 应用基础配置：提供可替换的时钟，测试中以可控时钟覆盖。
 */
@Configuration
public class AppConfig {

    /**
     * 系统时钟 Bean；生产环境使用系统默认时区时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
