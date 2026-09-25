package com.example.starter.survey;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 可控固定时钟：测试中随时调整“当前 UTC 日期”。
 */
@TestConfiguration
public class MutableClockConfig {

    private static volatile Clock current = Clock.fixed(
            LocalDate.of(2026, 6, 10).atStartOfDay().toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);

    @Bean
    @Primary
    public Clock fixedClock() {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return current;
            }

            @Override
            public Instant instant() {
                return current.instant();
            }
        };
    }

    /** 将“今天”设置为指定 UTC 日期。 */
    public static void setToday(LocalDate date) {
        current = Clock.fixed(date.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }
}
