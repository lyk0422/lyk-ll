package com.example.starter.blend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源配置：业务时间统一走 Clock，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock businessClock() {
        return Clock.systemDefaultZone();
    }
}
