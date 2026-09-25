package com.example.starter.dye.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：测试可注入固定时钟，生产使用系统默认时区（Jackson 按 Asia/Shanghai 输出）。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemDefaultZone();
    }
}
