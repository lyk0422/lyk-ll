package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定起点，可显式推进，用于租约到期、指令过期与时钟回拨场景。
 */
public class MutableClock extends Clock {

    private long millis;

    public MutableClock(long initialMillis) {
        this.millis = initialMillis;
    }

    public synchronized void setMillis(long millis) {
        this.millis = millis;
    }

    public synchronized void advance(long deltaMillis) {
        this.millis += deltaMillis;
    }

    @Override
    public synchronized long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
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
