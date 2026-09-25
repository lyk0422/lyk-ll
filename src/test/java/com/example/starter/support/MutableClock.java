package com.example.starter.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试中显式设定/推进时间，用于 30 分钟恰超时等边界验证。
 */
public class MutableClock extends Clock {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Instant instant;

    public MutableClock(LocalDateTime initial) {
        this.instant = initial.atZone(ZONE).toInstant();
    }

    public void setTime(LocalDateTime time) {
        this.instant = time.atZone(ZONE).toInstant();
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return ZoneOffset.UTC.equals(zone) ? Clock.fixed(instant, zone) : this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
