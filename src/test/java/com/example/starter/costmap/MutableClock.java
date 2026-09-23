package com.example.starter.costmap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：可设置与推进当前时刻，用于 TTL 边界的确定性验证。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock utc(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant), ZoneId.of("UTC"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advanceMillis(long millis) {
        this.instant = this.instant.plusMillis(millis);
    }
}
