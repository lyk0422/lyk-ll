package com.example.starter.ledger.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 台账服务基础配置：统一 UTC 时钟（测试可替换）与阈值属性。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfig {

    /** 业务统一使用 UTC 时钟，测试中可替换为可控时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
