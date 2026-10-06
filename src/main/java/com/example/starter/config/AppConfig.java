package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置；提供可替换的时钟便于时间相关测试。
 */
@Configuration
public class AppConfig {

    /**
     * 应用时钟，默认系统时钟（Asia/Shanghai 由 Jackson 时区配置负责展示层）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
