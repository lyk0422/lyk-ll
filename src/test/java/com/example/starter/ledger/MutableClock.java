package com.example.starter.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试用可控 UTC 时钟，可随时固定或推进。
 */
public final class MutableClock extends Clock {

    private Clock delegate;

    private MutableClock() {
        reset();
    }

    public static MutableClock create() {
        return new MutableClock();
    }

    /** 固定到指定时刻（UTC）。 */
    public void setInstant(Instant instant) {
        this.delegate = Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** 在当前时刻基础上推进。 */
    public void advance(java.time.Duration duration) {
        setInstant(instant().plus(duration));
    }

    /** 恢复为真实系统 UTC 时钟。 */
    public void reset() {
        this.delegate = Clock.systemUTC();
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
