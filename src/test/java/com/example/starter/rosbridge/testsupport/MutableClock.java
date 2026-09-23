package com.example.starter.rosbridge.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可变时钟：初始固定在一个非零时刻，{@link #advance(long)} 手工推进，
 * 供 Service 超时、断连宽限期等时间相关断言使用。
 */
public class MutableClock extends Clock {

    private volatile long millis = 1_000L;
    private final ZoneId zone = ZoneOffset.UTC;

    public void advance(long deltaMillis) {
        this.millis += deltaMillis;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }
}
