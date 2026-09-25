package com.example.starter.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可控制时钟：生产环境使用 Asia/Shanghai 系统时钟；测试中可 {@link #travelTo(Instant)} 定点推进。
 */
public class ApplicationClock extends Clock {

    /** 业务时间统一按上海时区解释与落库。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Clock delegate = Clock.system(ZONE);

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }

    /** 将时钟固定到指定时刻（测试用）。 */
    public void travelTo(Instant instant) {
        this.delegate = Clock.fixed(instant, ZONE);
    }

    /** 将时钟固定到上海时区的指定本地时刻（测试用）。 */
    public void travelTo(java.time.LocalDateTime dateTime) {
        travelTo(dateTime.atZone(ZONE).toInstant());
    }

    /** 在当前时钟基础上推进固定分钟数（测试用）。 */
    public void advanceMinutes(long minutes) {
        this.delegate = Clock.fixed(instant().plusSeconds(minutes * 60L), ZONE);
    }

    /** 恢复为真实系统时钟（测试用）。 */
    public void reset() {
        this.delegate = Clock.system(ZONE);
    }
}
