package com.example.starter.waterlogging.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时刻以请求报文为准，服务端仅用于 created_at 等审计字段；
 * 测试可注入固定/可调时钟实现确定性验证。
 */
@Configuration
public class ClockConfig {

    /** 业务统一时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock waterClock() {
        return Clock.system(ZONE);
    }
}
