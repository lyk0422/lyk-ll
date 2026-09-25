package com.example.starter.survey.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可变时钟：可在场景推进时设置当前时刻，模拟窗口首末日、30 日复查边界等。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zone = ZoneOffset.UTC;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    /** 设置到指定 UTC 日期的 00:00。 */
    public void setUtcDate(java.time.LocalDate date) {
        this.instant = date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock clock = new MutableClock(instant);
        return clock;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
