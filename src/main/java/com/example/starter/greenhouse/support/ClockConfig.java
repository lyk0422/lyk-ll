package com.example.starter.greenhouse.support;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可控时钟配置：业务与测试统一通过 {@link Clock} 取当前时刻（UTC），
 * 并发与时间边界测试可注入固定/偏移时钟。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
