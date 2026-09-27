package com.example.starter.damage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可控时钟：时间相关测试用，可定点设置与推进，避免依赖真实时间与长时间休眠。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private ZoneId zone;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zone = ZoneId.of("UTC");
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        MutableClock copy = new MutableClock(instant);
        copy.zone = newZone;
        return copy;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
