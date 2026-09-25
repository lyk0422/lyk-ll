package com.example.starter.forestry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：线程安全地固定/推进当前 UTC 日期。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(LocalDate date) {
        setDate(date);
    }

    public void setDate(LocalDate date) {
        this.instant = date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public void plusDays(long days) {
        setDate(LocalDate.ofInstant(instant, ZoneOffset.UTC).plusDays(days));
    }

    public LocalDate today() {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
