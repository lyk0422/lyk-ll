package com.example.starter.dispatch.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：默认固定在基准时刻，可由用例显式拨快验证超时边界；
 * 也可切换为系统 UTC 时钟，用于需要真实时间窗口的并发竞态测试。
 */
public class MutableClock extends Clock {

    private volatile Instant instant = Instant.parse("2026-09-25T10:00:00Z");
    private volatile boolean followSystem = false;

    public void set(Instant instant) {
        this.followSystem = false;
        this.instant = instant;
    }

    /** 切换为跟随系统时钟（真实流逝），用于竞态测试。 */
    public void useSystemClock() {
        this.followSystem = true;
    }

    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }

    public void advanceMillis(long millis) {
        this.instant = instant.plusMillis(millis);
    }

    @Override
    public Instant instant() {
        return followSystem ? Instant.now() : instant;
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
