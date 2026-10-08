package com.example.starter.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 可控时钟：默认使用系统 UTC；测试可替换为固定时钟，用于检修窗口边界等时刻判定。
 * 上客、复开等入口每次实时读取该时钟，不依赖后台扫描。
 */
@Component
public class ControlClock {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 当前时刻（UTC）。
     */
    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 替换底层时钟（主要供测试固定时刻）。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 固定到指定时刻。
     */
    public void setFixed(Instant instant) {
        this.clock = Clock.fixed(instant, java.time.ZoneOffset.UTC);
    }

    /**
     * 恢复系统 UTC 时钟。
     */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
