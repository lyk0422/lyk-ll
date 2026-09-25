package com.example.starter.survey.testsupport;

import com.example.starter.survey.support.TimeProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 测试时钟配置：以固定的可变 UTC 时钟替换系统时钟，
 * 默认落在合成船舶年度检验窗口内的 2026-06-15（周年日当天）。
 */
@TestConfiguration
public class FixedClockTestConfig {

    public static final MutableClock CLOCK =
            new MutableClock(LocalDate.of(2026, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant());

    @Bean
    @Primary
    public Clock testClock() {
        return CLOCK;
    }

    @Bean
    @Primary
    public TimeProvider testTimeProvider(Clock clock) {
        return new TimeProvider(clock);
    }
}
