package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * 业务时钟：固定 Asia/Shanghai 营业日；测试可替换为可控时钟。
 */
@Component
public class BusinessClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Clock clock;

    public BusinessClock() {
        this(Clock.system(ZONE));
    }

    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
