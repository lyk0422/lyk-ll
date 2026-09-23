package com.example.starter.spectrum.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 频谱租约协调模块的 Spring 配置入口。
 */
@Configuration
@EnableConfigurationProperties(SpectrumProperties.class)
public class SpectrumConfig {

    /**
     * 统一时钟：生产环境为 UTC 系统时钟；测试可注入固定/偏移时钟。
     */
    @Bean
    public Clock spectrumClock() {
        return Clock.systemUTC();
    }
}
