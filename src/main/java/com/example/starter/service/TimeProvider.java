package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

/**
 * 时间来源，生产环境使用 UTC 系统时钟，测试中替换为可控时钟。
 */
public class TimeProvider {

    private final Clock clock;

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
