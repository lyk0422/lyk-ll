package com.example.starter.calibration.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 统一时间来源，测试可替换为固定时钟，保证时间相关用例可控。
 */
@Component
public class TimeService {

    private volatile Clock clock = Clock.systemUTC();

    /** 替换时钟（仅供测试）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 当前 UTC 时刻。 */
    public Instant now() {
        return Instant.now(clock);
    }
}
