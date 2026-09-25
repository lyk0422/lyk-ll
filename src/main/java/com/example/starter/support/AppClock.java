package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * 统一时钟：生产使用 UTC 系统时钟；测试可替换为固定/可控时钟，便于验证 15 个自然日边界。
 */
@Component
public class AppClock {

    private volatile Clock clock = Clock.system(ZoneOffset.UTC);

    /** 当前时刻（UTC）。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 替换时钟（测试支持）。 */
    public void setClock(Clock replacement) {
        this.clock = replacement;
    }

    /** 恢复系统 UTC 时钟（测试支持）。 */
    public void reset() {
        this.clock = Clock.system(ZoneOffset.UTC);
    }
}
