package com.example.starter.portability;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控时钟：线程安全地固定/移动当前时刻，用于授权码失效与生效窗口边界验证。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(initial, ZoneId.of("UTC"));
    }

    public MutableClock(Instant initial, ZoneId zone) {
        this.instant = new AtomicReference<>(initial);
        this.zone = zone;
    }

    public void setInstant(Instant newInstant) {
        this.instant.set(newInstant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant.get(), newZone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
