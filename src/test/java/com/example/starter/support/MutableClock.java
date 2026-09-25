package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试用，可推进与重置，避免依赖真实时间。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
        this.zone = ZoneOffset.UTC;
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
        return instant.get();
    }

    /**
     * 直接设置当前时刻。
     */
    public void set(Instant newInstant) {
        instant.set(newInstant);
    }
}
