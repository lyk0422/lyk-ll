package com.example.starter.pension.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * UTC 时钟：司龄按入职日至支付申请日（UTC 日界）整年计算，期次按月初（UTC）到期。
 * 默认使用系统 UTC 时钟，测试可替换为固定时钟。
 */
public class UtcClock {

    private final Clock clock;

    public UtcClock() {
        this(Clock.systemUTC());
    }

    public UtcClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return 当前 UTC 时刻。
     */
    public Instant nowInstant() {
        return Instant.now(clock);
    }

    /**
     * @return 当前 UTC 日期（日界）。
     */
    public LocalDate todayUtc() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
