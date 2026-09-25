package com.example.starter.food;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：时间相关用例显式设定时刻，不依赖真实当前时间与休眠。
 */
public class ControllableClock extends Clock {

    private volatile Instant instant = Instant.parse("2026-02-01T00:00:00Z");

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
