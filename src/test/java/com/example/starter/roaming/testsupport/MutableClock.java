package com.example.starter.roaming.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控 UTC 时钟：可固定、可快进，便于验证授权 5 分钟边界等时间规则。
 */
public class MutableClock extends Clock {

    private volatile Instant current;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.current = initial;
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
        return current;
    }

    /** 快进指定时长。 */
    public void advance(Duration duration) {
        this.current = current.plus(duration);
    }

    /** 直接设置当前时刻。 */
    public void setInstant(Instant instant) {
        this.current = instant;
    }
}
