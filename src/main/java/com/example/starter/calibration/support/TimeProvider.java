package com.example.starter.calibration.support;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 可替换的系统时钟 Bean，测试中可固定或拨动时间。
 */
@Component
public class TimeProvider {

    private Clock clock;

    public TimeProvider() {
        this(Clock.systemUTC());
    }

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    /** 替换底层时钟（主要供测试固定/推进时间）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
