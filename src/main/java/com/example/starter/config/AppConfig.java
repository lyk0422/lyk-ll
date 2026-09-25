package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 应用基础配置：提供时钟 Bean，便于测试以可控时钟替换。
 */
@Configuration
public class AppConfig {

    /**
     * 系统UTC时钟：记录创建/更新时间的默认来源。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
