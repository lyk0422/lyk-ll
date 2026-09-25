package com.example.starter.cutover.config;

import com.example.starter.cutover.support.TimeSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 业务 Bean 配置；默认使用系统时钟，测试中以固定/可调 Clock 覆盖。
 */
@Configuration
public class CutoverConfig {

    @Bean
    public Clock systemClock() {
        return Clock.system(TimeSource.ZONE);
    }

    @Bean
    public TimeSource timeSource(Clock clock) {
        return new TimeSource(clock);
    }
}
