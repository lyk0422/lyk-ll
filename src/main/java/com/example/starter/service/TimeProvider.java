package com.example.starter.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 时间来源：默认系统 UTC 时钟；测试可替换为固定时钟以覆盖窗口恰边界。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 当前时刻（UTC）。
     */
    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 固定当前时刻（测试用）。
     */
    public void useFixedClock(Instant instant) {
        this.clock = Clock.fixed(instant, ZoneOffset.UTC);
    }

    /**
     * 恢复系统 UTC 时钟。
     */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
