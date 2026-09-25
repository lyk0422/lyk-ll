package com.example.starter.weight.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可固定或推进当前时间，用于复检 7 日 UTC 日界边界测试。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private volatile ZoneId zone;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zone = ZoneOffset.UTC;
    }

    /**
     * 将时钟固定到指定时刻。
     */
    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        this.zone = zone;
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
