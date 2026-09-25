package com.example.starter.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试中可固定、精确推进，用于验证 4 小时稳定与 24 小时窗口边界。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
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
        return instant;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }

    public void advanceMillis(long millis) {
        this.instant = instant.plusMillis(millis);
    }
}
