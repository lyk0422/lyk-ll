package com.example.starter.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：统一使用 UTC 时钟，业务代码通过注入 {@link Clock} 取当前时刻，
 * 测试中替换为可控时钟以验证 4 小时押运时限与第 3 日销账边界。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
