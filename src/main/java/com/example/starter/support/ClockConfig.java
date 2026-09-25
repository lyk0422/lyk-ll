package com.example.starter.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 基础 Bean 配置：提供全服务统一使用的时钟。
 *
 * <p>默认使用 UTC 系统时钟；测试中可替换为固定或可推进的 {@link Clock}，
 * 以精确验证“稳定 4 小时”“24 小时内最多 3 次”等时间边界。</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
