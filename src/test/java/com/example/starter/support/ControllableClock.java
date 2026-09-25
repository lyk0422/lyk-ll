package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定在某个 UTC 时刻，可随时拨针，用于争议期边界与封账测试。
 */
public class ControllableClock extends Clock {

    private volatile Instant fixed;

    public ControllableClock(Instant fixed) {
        this.fixed = fixed;
    }

    /** 拨到指定时刻。 */
    public void setInstant(Instant instant) {
        this.fixed = instant;
    }

    /** 前进指定秒数。 */
    public void advanceSeconds(long seconds) {
        this.fixed = fixed.plusSeconds(seconds);
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
        return fixed;
    }
}
