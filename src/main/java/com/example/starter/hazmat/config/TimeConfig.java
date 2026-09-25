package com.example.starter.hazmat.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务统一通过 Clock 取当前时刻，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    public static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock clock() {
        return Clock.system(APP_ZONE);
    }
}
