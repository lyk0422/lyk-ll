package com.example.starter.payment.support;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 可替换时钟：生产环境为 UTC 系统时钟，测试中可通过 {@link #setClock(Clock)} 注入固定时钟，
 * 以便确定性验证 16:00 UTC 日切边界。
 */
@Component
public class TimeProvider {

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
