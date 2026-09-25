package com.example.starter.rt.web;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：默认系统时钟（Asia/Shanghai），测试可注入固定/偏移时钟实现时间可控。
 */
@Configuration
public class ClockConfig {

    /** 业务统一时区。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
