package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：可固定与推进业务时间，避免时间相关测试依赖真实时钟。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public synchronized void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    public synchronized void plusSeconds(long seconds) {
        this.instant = this.instant.plusSeconds(seconds);
    }
}
