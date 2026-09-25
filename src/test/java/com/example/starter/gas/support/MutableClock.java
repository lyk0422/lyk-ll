package com.example.starter.gas.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试中可随时将“当前时刻”固定到指定 Instant（UTC）。
 */
public class MutableClock extends Clock {

    private volatile Clock delegate = Clock.fixed(Instant.parse("2026-09-26T00:30:00Z"), ZoneOffset.UTC);

    public void setInstant(Instant instant) {
        this.delegate = Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
