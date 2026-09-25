package com.example.starter.carbon;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用时钟配置：统一使用 UTC 时钟，测试中可替换为可控时钟以验证截止时刻口径。
 */
@Configuration
public class ClockConfig {

    /**
     * 提供 UTC 系统时钟。
     */
    @Bean
    public Clock carbonClock() {
        return Clock.systemUTC();
    }
}
