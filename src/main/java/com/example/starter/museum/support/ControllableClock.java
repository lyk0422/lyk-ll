package com.example.starter.museum.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：生产环境走系统 UTC 时钟，单元/并发测试可固定到任意时刻，
 * 保证 60 个自然日、24 小时等边界在确定性时间下验证。
 */
public class ControllableClock extends Clock {

    private volatile Instant fixed;

    public void setInstant(Instant instant) {
        this.fixed = instant;
    }

    public void reset() {
        this.fixed = null;
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
        return fixed != null ? fixed : Instant.now();
    }
}
