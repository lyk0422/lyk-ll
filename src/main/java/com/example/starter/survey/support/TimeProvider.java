package com.example.starter.survey.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 时间来源：生产使用系统 UTC 时钟，测试可注入固定时钟。
 * 周年日与窗口一律按 UTC 日界计算。
 */
public class TimeProvider {

    private final Clock clock;

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    /** 当前时刻（UTC）。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 当前 UTC 日期。 */
    public LocalDate todayUtc() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
