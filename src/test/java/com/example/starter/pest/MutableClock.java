package com.example.starter.pest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可变时钟：支持设置与推进时间，用于验证 14 天监测期等时间规则。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    /**
     * 直接设置当前时间。
     */
    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    /**
     * 推进当前时间。
     */
    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }
}
