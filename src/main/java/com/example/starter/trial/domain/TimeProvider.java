package com.example.starter.trial.domain;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 统一 UTC 时间源；测试可替换为固定时钟以验证 UTC 日界与终态时刻。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** 测试专用：替换底层时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 测试专用：恢复系统 UTC 时钟。 */
    public void resetClock() {
        this.clock = Clock.systemUTC();
    }
}
