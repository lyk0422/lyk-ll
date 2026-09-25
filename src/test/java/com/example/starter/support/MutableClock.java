package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试中可随时设置固定时刻，用于复检 15 个自然日（UTC 日界）边界验证。
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;
    private final ZoneId zone;

    public MutableClock(Instant fixed) {
        this(fixed, ZoneOffset.UTC);
    }

    public MutableClock(Instant fixed, ZoneId zone) {
        this.fixed = fixed;
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        this.fixed = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(fixed, zone);
    }

    @Override
    public Instant instant() {
        return fixed;
    }
}
