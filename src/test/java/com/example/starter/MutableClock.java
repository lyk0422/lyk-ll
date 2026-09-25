package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控 UTC 时钟：测试可随时把“当前时刻”推进到指定纪元秒。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-26T00:00:00Z"));

    public void setEpochSeconds(long epochSeconds) {
        now.set(Instant.ofEpochSecond(epochSeconds));
    }

    public void advanceSeconds(long seconds) {
        now.updateAndGet(i -> i.plusSeconds(seconds));
    }

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
        return now.get();
    }
}
