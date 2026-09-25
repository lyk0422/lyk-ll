package com.example.starter.flood;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可随时设置当前时刻，用于精确验证“恰满 15 分钟”等时间边界。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock at(String utcIso) {
        return new MutableClock(Instant.parse(utcIso), ZoneOffset.UTC);
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advanceMinutes(long minutes) {
        this.instant = instant.atZone(zone).plusMinutes(minutes).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
