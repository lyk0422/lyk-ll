package com.example.starter.ili;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础设施 Bean：时钟抽象，便于测试使用可控时钟。
 */
@Configuration
public class IliConfig {

    /**
     * 系统默认时区时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
