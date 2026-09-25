package com.example.starter.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时间配置：以可控 UTC 时钟替换系统时钟。
 */
@TestConfiguration
public class TestTimeConfig {

    /** 测试基准时刻（UTC）。 */
    public static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
