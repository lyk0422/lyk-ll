package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可推进的测试时钟：用于证书有效期边界（now<截止有效、恰截止失效）与
 * 证书过期后读数告警等时间相关场景，避免依赖真实时间流逝。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
