package com.example.starter.scene.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 基础 Bean 配置；时钟独立成 Bean 以便测试中使用固定时间。
 */
@Configuration
public class SceneClockConfiguration {

    /**
     * 应用使用 UTC 时钟；版本提交时间戳来源。
     */
    @Bean
    public Clock sceneClock() {
        return Clock.systemUTC();
    }
}
