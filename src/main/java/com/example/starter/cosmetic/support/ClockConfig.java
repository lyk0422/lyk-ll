package com.example.starter.cosmetic.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 时间配置。生产使用系统 UTC 时钟；测试可注入
 * {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} 等可控时钟。
 */
@Configuration
@EnableScheduling
public class ClockConfig {

    /** 全局业务时钟，固定 UTC 口径。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
