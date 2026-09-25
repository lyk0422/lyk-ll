package com.example.starter.crane;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：默认系统 UTC 时钟；测试可注入固定/偏移时钟以精确验证 3 秒制动口径。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
