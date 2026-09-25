package com.example.starter.dispatch;

import com.example.starter.dispatch.service.DispatchClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定基准时刻替换系统时钟，便于精确控制超时边界。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final Instant BASE_TIME = Instant.parse("2026-09-25T00:00:00Z");

    @Bean
    @Primary
    public DispatchClock dispatchClock() {
        return new MutableDispatchClock(BASE_TIME);
    }
}
