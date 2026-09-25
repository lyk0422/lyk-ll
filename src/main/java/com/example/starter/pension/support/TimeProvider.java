package com.example.starter.pension.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * 系统时间来源。测试可替换为固定/可控时钟，业务时间统一取 UTC。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 替换底层时钟（主要供测试使用）。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return 当前 UTC 时间
     */
    public OffsetDateTime nowUtc() {
        return OffsetDateTime.now(clock);
    }
}
