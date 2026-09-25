package com.example.starter.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：以可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 全测试共享的可控时钟，实例稳定，便于用例内直接推进。 */
    public static final MutableClock CLOCK = MutableClock.create();

    @Bean
    @Primary
    public MutableClock clock() {
        return CLOCK;
    }
}
