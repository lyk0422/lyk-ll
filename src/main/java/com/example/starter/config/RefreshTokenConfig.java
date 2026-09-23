package com.example.starter.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 刷新令牌模块装配：启用配置属性并提供可控的 UTC 时钟（测试可替换）。
 */
@Configuration
@EnableConfigurationProperties(RefreshTokenProperties.class)
public class RefreshTokenConfig {

    /**
     * 业务统一使用的时钟，默认 UTC 系统时钟；测试中可替换为固定/可调时钟。
     */
    @Bean
    public Clock applicationClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
