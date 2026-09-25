package com.example.starter.lc.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控 UTC 时钟：可固定到任意时刻并推进，用于精确验证 5 个银行工作日 17:00 边界。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    public void setDateTime(LocalDateTime dateTime) {
        this.instant = dateTime.toInstant(ZoneOffset.UTC);
    }

    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }

    public void advanceDays(long days) {
        this.instant = instant.plusSeconds(days * 24 * 3600);
    }

    @Override
    public ZoneId getZone() {
        return zone;
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
