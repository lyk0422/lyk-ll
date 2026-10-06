package com.example.starter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：可重置、可推进，保证时间相关断言确定。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public synchronized void reset(Instant newInstant) {
        this.instant = newInstant;
    }

    public synchronized void plus(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    public synchronized void plusDays(long days) {
        this.instant = this.instant.plus(Duration.ofDays(days));
    }

    public synchronized void plusSeconds(long seconds) {
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
    public synchronized Instant instant() {
        return instant;
    }
}
