package com.example.starter.rights;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 可替换的 UTC 时钟；测试中可回拨/固定以构造撤销与排期的先后顺序。
 */
@Component
public class AppClock {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
