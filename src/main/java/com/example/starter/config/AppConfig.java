package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础组件配置；Clock 以 Bean 形式注入，便于测试使用可控时钟。
 */
@Configuration
public class AppConfig {

    /**
     * 系统时钟，时区为 JVM 默认时区（部署环境固定为 Asia/Shanghai）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
