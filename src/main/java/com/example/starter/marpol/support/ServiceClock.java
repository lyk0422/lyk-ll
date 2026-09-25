package com.example.starter.marpol.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 统一 UTC 时钟。生产使用系统 UTC 时钟；测试可替换为固定时钟以验证整改期限等时间口径。
 */
@Component
public class ServiceClock {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** UTC 日界下的当前日期。 */
    public LocalDate todayUtc() {
        return now().atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** 替换底层时钟（主要供测试使用）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 恢复系统 UTC 时钟。 */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
