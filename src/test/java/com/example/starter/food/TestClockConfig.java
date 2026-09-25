package com.example.starter.food;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：以可控 UTC 时钟替换系统时钟。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public Clock controllableClock() {
        return new ControllableClock();
    }
}
