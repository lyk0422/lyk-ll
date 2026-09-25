package com.example.starter.greenhouse.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定起点，可显式推进；默认锚定 2026-09-25T00:00:00Z。
 */
public class MutableClock extends Clock {

    private volatile Instant current;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock() {
        this(Instant.parse("2026-09-25T00:00:00Z"));
    }

    public MutableClock(Instant start) {
        this.current = start;
    }

    public void setInstant(Instant instant) {
        this.current = instant;
    }

    public void advance(Duration duration) {
        this.current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return current;
    }
}
