package com.example.starter.margin.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：业务时间统一来源，默认系统 UTC 时钟；测试可拨动到任意时刻以验证截止/逾期语义。
 */
@Component
public class MarginClock {

    private final AtomicReference<Clock> clock = new AtomicReference<>(Clock.systemUTC());

    public Instant now() {
        return Instant.now(clock.get());
    }

    /** 用固定时刻替换时钟（测试用）。 */
    public void setFixed(Instant instant) {
        clock.set(Clock.fixed(instant, java.time.ZoneOffset.UTC));
    }

    /** 恢复系统时钟。 */
    public void reset() {
        clock.set(Clock.systemUTC());
    }
}
