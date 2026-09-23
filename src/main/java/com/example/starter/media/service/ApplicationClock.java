package com.example.starter.media.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 可替换的 UTC 时钟；生产环境使用系统 UTC 时钟，单元/并发测试可固定或拨动时间。
 */
@Component
public class ApplicationClock {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
