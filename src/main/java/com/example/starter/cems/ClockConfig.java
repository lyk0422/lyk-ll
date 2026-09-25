package com.example.starter.cems;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间配置：业务统一使用 Asia/Shanghai 时区的可控时钟，测试可替换时钟实现边界时刻。
 */
@Configuration
public class ClockConfig {

    /** 业务统一时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock cemsClock() {
        return Clock.system(ZONE);
    }
}
