package com.example.starter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 测试用可控时钟：可设定与推进，避免时间相关测试依赖真实时间或长时间休眠。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(LocalDateTime dateTime, ZoneId zone) {
        this.instant = dateTime.atZone(zone).toInstant();
        this.zone = zone;
    }

    public void set(LocalDateTime dateTime) {
        this.instant = dateTime.atZone(zone).toInstant();
    }

    public void plus(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    public LocalDateTime nowDateTime() {
        return LocalDateTime.ofInstant(instant, zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(LocalDateTime.ofInstant(instant, zone), zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
