package com.example.starter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可设定与推进当前时间（UTC）。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    /**
     * 设定当前时间。
     */
    public void set(Instant newInstant) {
        this.instant = newInstant;
    }

    /**
     * 推进当前时间。
     */
    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
