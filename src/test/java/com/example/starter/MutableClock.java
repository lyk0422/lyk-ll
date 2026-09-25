package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试用，可定点、可步进，始终 UTC。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
    }

    /**
     * 定点到指定 UTC 瞬时。
     */
    public void set(Instant newInstant) {
        instant.set(newInstant);
    }

    /**
     * 步进指定秒数。
     */
    public void advanceSeconds(long seconds) {
        instant.updateAndGet(i -> i.plusSeconds(seconds));
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
        return instant.get();
    }
}
