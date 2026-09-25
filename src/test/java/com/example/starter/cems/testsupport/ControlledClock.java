package com.example.starter.cems.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可控时钟：测试按业务场景显式推进到边界时刻（恰 2 小时、恰连续 3 次等）。
 */
public class ControlledClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public ControlledClock(Instant initial, ZoneId zone) {
        this.instant = initial;
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    /** 直接设置为业务时区（Asia/Shanghai）本地时刻。 */
    public void setLocal(String localDateTime) {
        this.instant = java.time.LocalDateTime.parse(localDateTime).atZone(zone).toInstant();
    }

    /** 返回推进后的同一时钟（小时数可为负）。 */
    public ControlledClock advanceHours(long hours) {
        instant = instant.atZone(zone).plusHours(hours).toInstant();
        return this;
    }

    /** 当前业务时区本地时刻。 */
    public java.time.LocalDateTime localNow() {
        return java.time.LocalDateTime.ofInstant(instant, zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ControlledClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public static ControlledClock atShanghai(String localDateTime) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant instant = java.time.LocalDateTime.parse(localDateTime).atZone(zone).toInstant();
        return new ControlledClock(instant, zone);
    }
}
