package com.example.starter.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试可显式推进时间，用于验证 in-flight 超时重试，不依赖真实休眠。
 */
public class MutableTestClock extends Clock {

    private volatile Instant instant = Instant.parse("2026-01-01T00:00:00Z");

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

    /** 向前推进指定时长。 */
    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }
}
