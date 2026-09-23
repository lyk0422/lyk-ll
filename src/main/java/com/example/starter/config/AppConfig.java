package com.example.starter.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础 Bean 配置：可控时钟，便于测试超时与恢复。
 */
@Configuration
@EnableConfigurationProperties(ForwarderProperties.class)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
