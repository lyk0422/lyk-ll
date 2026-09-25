package com.example.starter.museum;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试专用，可固定与推进时间，线程安全。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(initial, ZoneId.of("UTC"));
    }

    public MutableClock(Instant initial, ZoneId zone) {
        this.current = new AtomicReference<>(initial);
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        current.set(instant);
    }

    public void advance(Duration duration) {
        current.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(current.get(), zone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
