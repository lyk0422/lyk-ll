package com.example.starter.carbon;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以 @Primary 可控时钟替换生产 UTC 时钟。
 */
@TestConfiguration
public class MutableClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2030-06-01T00:00:00Z"));
    }
}
