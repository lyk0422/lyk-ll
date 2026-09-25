package com.example.starter.reinsurance.testsupport;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用固定 UTC 时钟替换生产时钟，默认 2026-08-15（2026-Q3）。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant DEFAULT_NOW =
            Instant.parse("2026-08-15T10:00:00Z");

    @Bean
    @Primary
    public SettableClock settableClock() {
        return new SettableClock(DEFAULT_NOW);
    }

    public static java.time.LocalDate defaultUtcDate() {
        return DEFAULT_NOW.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
