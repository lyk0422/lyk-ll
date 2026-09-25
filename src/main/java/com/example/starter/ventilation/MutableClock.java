package com.example.starter.ventilation;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：生产环境使用系统 UTC 时钟，测试环境可推进/固定时间，
 * 以便确定性验证 60 秒恰超时等时间边界。
 */
public class MutableClock {

    private final AtomicReference<Clock> clock = new AtomicReference<>(Clock.systemUTC());

    /** 当前 epoch 毫秒。 */
    public long nowMillis() {
        return Instant.now(clock.get()).toEpochMilli();
    }

    /** 替换底层时钟（测试用）。 */
    public void setClock(Clock newClock) {
        clock.set(newClock);
    }

    /** 恢复系统时钟。 */
    public void reset() {
        clock.set(Clock.systemUTC());
    }
}
