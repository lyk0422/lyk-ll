package com.example.starter.irrigation.config;

import com.example.starter.irrigation.support.TurnClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间相关 Bean：统一使用 UTC 系统时钟；测试可注入固定时钟实现可控时间。
 */
@Configuration
public class TimeConfig {

    /** 全局时钟 Bean，默认系统 UTC。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 7 天 UTC 轮次时钟。 */
    @Bean
    public TurnClock turnClock(Clock clock) {
        return new TurnClock(clock);
    }
}
