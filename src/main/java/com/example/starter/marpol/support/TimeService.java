package com.example.starter.marpol.support;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 统一时间来源，固定 UTC 时区；测试可替换为可控时钟。
 */
@Component
public class TimeService {

    private final Clock clock;

    public TimeService() {
        this(Clock.systemUTC());
    }

    public TimeService(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
