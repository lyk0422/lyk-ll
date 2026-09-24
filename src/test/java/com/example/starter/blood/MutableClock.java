package com.example.starter.blood;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：线程安全，可任意设定当前 UTC 时刻以覆盖效期边界。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    /**
     * 设定当前时刻。
     *
     * @param newInstant 新的当前时刻（UTC）
     */
    public synchronized void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public synchronized Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
