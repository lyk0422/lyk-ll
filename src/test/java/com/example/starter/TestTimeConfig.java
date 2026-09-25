package com.example.starter;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时间配置：以可控时钟替换系统时钟，用于授权码失效与生效窗口的边界验证。
 */
@TestConfiguration
public class TestTimeConfig {

    /** 测试基准时刻：2026-09-25T10:00:00Z（生效窗口之外）。 */
    public static final Instant BASE_TIME = Instant.parse("2026-09-25T10:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME);
    }
}
