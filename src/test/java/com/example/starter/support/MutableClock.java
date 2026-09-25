package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控时钟：线程安全，可任意拨动当前时刻。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
    }

    public void set(Instant newInstant) {
        this.instant.set(newInstant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
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
