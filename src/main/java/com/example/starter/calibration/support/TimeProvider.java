package com.example.starter.calibration.support;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 可控时钟：测试可替换固定时钟，生产使用系统 UTC 时钟。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    public java.time.Instant now() {
        return java.time.Instant.now(clock);
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
