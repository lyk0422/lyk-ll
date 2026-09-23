package com.example.starter.lot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 业务基础配置：统一使用 Asia/Shanghai 时区的可控时钟，测试可替换为固定时钟。
 */
@Configuration
public class LotConfig {

    /** 业务时间所在时区。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
