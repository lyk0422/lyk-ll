package com.example.starter.hazmat.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可控时钟：时间相关测试通过 setInstant/plus 精确推进，避免真实等待。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    public void plusSeconds(long seconds) {
        this.instant = this.instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
