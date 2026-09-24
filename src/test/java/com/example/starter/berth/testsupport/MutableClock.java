package com.example.starter.berth.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试中可随时设置当前时刻（UTC），用于验证到期生效、迟到审批等时间相关行为。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
