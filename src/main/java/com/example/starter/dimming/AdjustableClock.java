package com.example.starter.dimming;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可调时钟：业务时间统一取自该时钟，测试与合成演示可推进时间，
 * 用于验证稳定时限、滚动窗口、日结等时间相关规则。
 */
public class AdjustableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public AdjustableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new AdjustableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public synchronized void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    public synchronized void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }
}
