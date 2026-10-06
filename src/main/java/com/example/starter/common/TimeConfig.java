package com.example.starter.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务时间统一走可替换的 {@link Clock}（默认 UTC 系统时钟），
 * 便于差异单 72 小时升级规则在测试中使用可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 应用时钟，默认 UTC 系统时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
