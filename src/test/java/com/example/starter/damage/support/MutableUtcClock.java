package com.example.starter.damage.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控 UTC 时钟：固定起点后可随时拨针，用于验证报价截止与开标时刻边界。
 */
public class MutableUtcClock extends Clock {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private Instant instant;

    public MutableUtcClock(Instant initial) {
        this.instant = initial;
    }

    public static MutableUtcClock startAt(LocalDateTime time) {
        return new MutableUtcClock(time.toInstant(ZoneOffset.UTC));
    }

    public void setTime(LocalDateTime time) {
        this.instant = time.toInstant(ZoneOffset.UTC);
    }

    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
