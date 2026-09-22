package com.example.starter.ros.support;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 时间来源,封装 {@link Clock};测试可替换为固定时钟以获得可控时间。
 */
@Component
public class TimeProvider {

    private final Clock clock;

    public TimeProvider() {
        this(Clock.systemDefaultZone());
    }

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
