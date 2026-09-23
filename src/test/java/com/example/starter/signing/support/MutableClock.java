package com.example.starter.signing.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可固定或推进时间，用于验证到期竞态等时间相关行为。
 */
public class MutableClock extends Clock {

    private Instant current;
    private ZoneId zone;

    public MutableClock(Instant initial) {
        this.current = initial;
        this.zone = ZoneOffset.UTC;
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
        MutableClock clock = new MutableClock(current);
        clock.zone = zone;
        return clock;
    }

    @Override
    public Instant instant() {
        return current;
    }
}
