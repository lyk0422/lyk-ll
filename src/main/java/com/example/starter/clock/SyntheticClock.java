package com.example.starter.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可注入的合成时钟：默认跟随系统 UTC 时间，测试可固定或推进。
 * 所有业务时间均为 UTC 毫秒。
 */
public class SyntheticClock extends Clock {

    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        Instant pinned = fixed.get();
        return pinned != null ? pinned : Instant.now();
    }

    /**
     * 当前 UTC 毫秒时间戳。
     */
    public long nowMs() {
        return instant().toEpochMilli();
    }

    /**
     * 固定时钟到指定 UTC 毫秒；测试专用。
     */
    public void fixAt(long epochMs) {
        fixed.set(Instant.ofEpochMilli(epochMs));
    }

    /**
     * 恢复跟随系统时间。
     */
    public void release() {
        fixed.set(null);
    }
}
