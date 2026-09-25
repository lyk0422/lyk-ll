package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控时钟：时间相关用例通过 setInstant 精确控制业务时间（UTC）。
 */
public class MutableClock extends Clock {

    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-08-15T10:00:00Z");

    private final AtomicReference<Instant> instant = new AtomicReference<>(DEFAULT_INSTANT);

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    public void setInstant(Instant newInstant) {
        instant.set(newInstant);
    }

    public void reset() {
        instant.set(DEFAULT_INSTANT);
    }
}
