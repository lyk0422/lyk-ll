package com.example.starter.waste;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 台账时间源配置：统一使用 Asia/Shanghai 时区时钟，测试可替换为可控时钟。
 */
@Configuration
public class WasteConfig {

    /**
     * 业务时钟，所有称重、回执、日结时间均取自该时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
