package com.example.starter.reinsurance.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间配置：业务时间一律从注入的 {@link Clock} 取 UTC 时间，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
