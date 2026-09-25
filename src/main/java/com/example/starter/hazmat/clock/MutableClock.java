package com.example.starter.hazmat.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 可控时钟：测试中可显式设定"当前时刻"，便于复算超时边界；默认使用系统时钟（Asia/Shanghai）。
 */
public class MutableClock extends Clock {

    /** 业务统一时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Instant instant;
    private volatile ZoneId zone = ZONE;

    public MutableClock() {
        this.instant = null;
    }

    /** 设定当前时刻（仅用于测试/管理侧复算）。 */
    public void setTime(LocalDateTime time) {
        this.instant = time.atZone(zone).toInstant();
    }

    /** 清除固定时刻，恢复跟随系统时钟。 */
    public void reset() {
        this.instant = null;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(this);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock c = new MutableClock();
        c.instant = this.instant;
        c.zone = zone;
        return c;
    }

    @Override
    public Instant instant() {
        return instant != null ? instant : Instant.now();
    }
}
