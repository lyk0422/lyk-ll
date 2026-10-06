package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试用，按日历日推进，时区固定 Asia/Shanghai。
 */
public class MutableClock extends Clock {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AtomicReference<Instant> instant;

    public MutableClock(LocalDate date) {
        this.instant = new AtomicReference<>(date.atTime(9, 0).atZone(ZONE).toInstant());
    }

    public void setDate(LocalDate date) {
        instant.set(date.atTime(9, 0).atZone(ZONE).toInstant());
    }

    public void plusDays(long days) {
        instant.updateAndGet(i -> i.atZone(ZONE).plusDays(days).toInstant());
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
