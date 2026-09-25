package com.example.starter.cutover;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可调时钟：固定时区 Asia/Shanghai，可随时拨点，用于精确验证超时与窗口边界。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant = new AtomicReference<>();
    private final ZoneId zone;

    public MutableClock(Instant initial, ZoneId zone) {
        this.instant.set(initial);
        this.zone = zone;
    }

    public void setInstant(Instant newInstant) {
        instant.set(newInstant);
    }

    public void advanceSeconds(long seconds) {
        instant.updateAndGet(i -> i.plusSeconds(seconds));
    }

    public void advanceMinutes(long minutes) {
        instant.updateAndGet(i -> i.plusSeconds(minutes * 60));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant.get(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
