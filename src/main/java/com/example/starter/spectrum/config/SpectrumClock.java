package com.example.starter.spectrum.config;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 可替换时钟：生产使用 UTC 系统时钟，测试可注入固定时钟以验证时间相关边界。
 */
@Component
public class SpectrumClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前时钟。 */
    public Clock getClock() {
        return clock;
    }

    /** 替换时钟（测试使用）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 当前 epoch 毫秒（UTC）。 */
    public long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
