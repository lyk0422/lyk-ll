package com.example.starter.battery;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 统一时间来源（UTC），测试可替换为固定/偏移时钟。
 */
@Component
public class TimeProvider {

    private final Clock clock;

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    /** 当前 UTC 时间。 */
    public Instant now() {
        return clock.instant();
    }

    /** 当前 UTC epoch 毫秒。 */
    public long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
