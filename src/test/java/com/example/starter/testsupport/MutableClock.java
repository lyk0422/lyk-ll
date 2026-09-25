package com.example.starter.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试可固定或推进当前时刻，所有时间相关逻辑均以 UTC 解释。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    public void setInstant(Instant instant) {
        current.set(instant);
    }

    public void advanceSeconds(long seconds) {
        current.updateAndGet(t -> t.plusSeconds(seconds));
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
        return current.get();
    }
}
