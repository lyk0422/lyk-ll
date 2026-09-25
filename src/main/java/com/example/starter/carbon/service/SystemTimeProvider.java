package com.example.starter.carbon.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 系统时钟，测试中可替换为可控时钟，保证截止时刻口径可测。
 */
@Component
public class SystemTimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** 仅测试使用：替换底层时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 仅测试使用：恢复系统 UTC 时钟。 */
    public void resetClock() {
        this.clock = Clock.systemUTC();
    }
}
