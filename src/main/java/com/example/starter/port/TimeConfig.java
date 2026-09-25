package com.example.starter.port;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：统一使用 UTC 时钟，测试中可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟；授权码有效期与生效窗口均以此为准。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
