package com.example.starter.trial.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 统一 UTC 时钟：业务服务依赖 {@code Supplier<Instant>}，生产环境取系统 UTC 当前时刻，
 * 测试可注入固定/可控时钟以验证 UTC 日界与审计时序。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Supplier<Instant> utcClock() {
        return Instant::now;
    }
}
