package com.example.starter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 以可控 UTC 固定时钟替换系统时钟，默认固定在 2026-09-11T01:00:00Z。
 * 用独立 Bean 名并标 {@link Primary}，避免与生产 applicationClock 同名覆盖。
 */
@TestConfiguration
public class TestClockConfig {

    private final ControllableClock clockInstance =
            new ControllableClock(Instant.parse("2026-09-11T01:00:00Z"));

    /** 可控时钟 Bean（同时是 Clock 与 ControllableClock），对 Clock 注入优先生效。 */
    @Bean
    @Primary
    public ControllableClock controllableClock() {
        return clockInstance;
    }
}
