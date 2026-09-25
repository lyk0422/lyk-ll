package com.example.starter.atm.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 领域时钟：业务时间统一以 UTC epoch 毫秒计。
 * 默认跟随系统 UTC 时钟；测试可将其固定到任意时刻，用于 4 小时时限与第 3 自然日边界验证。
 */
@Component
public class DomainClock {

    private volatile Clock clock = Clock.systemUTC();

    public long nowMillis() {
        return Instant.now(clock).toEpochMilli();
    }

    public Instant now() {
        return Instant.now(clock);
    }

    /** 测试辅助：将时钟固定到指定时刻。 */
    public void setFixed(Instant instant) {
        this.clock = Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** 测试辅助：恢复系统时钟。 */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
