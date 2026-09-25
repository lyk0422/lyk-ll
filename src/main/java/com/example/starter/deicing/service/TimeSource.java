package com.example.starter.deicing.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 可替换的系统时钟，测试中可固定/拨动。
 */
@Component
public class TimeSource {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 固定时钟（测试支持）。
     */
    public void pin(Clock pinnedClock) {
        this.clock = pinnedClock;
    }
}
