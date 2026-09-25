package com.example.starter.cosmetic.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可固定/推进时刻，便于期限口径与并发场景复算。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private ZoneId zone;

    public MutableClock(Instant initial) {
        this.instant = initial;
        this.zone = ZoneOffset.UTC;
    }

    /** 固定到指定时刻。 */
    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        this.zone = zone;
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
