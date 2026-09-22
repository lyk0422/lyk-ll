package com.example.starter.perception.config;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 应用时钟：默认系统 UTC 时钟；测试可替换为固定时钟以保证时间相关场景可控。
 */
@Component
public class TimeService {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** 替换时钟（仅测试使用）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 恢复系统 UTC 时钟。 */
    public void resetClock() {
        this.clock = Clock.systemUTC();
    }
}
