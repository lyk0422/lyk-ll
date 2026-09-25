package com.example.starter.pump.testsupport;

import com.example.starter.pump.core.TimeProvider;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 测试可控时钟：可固定与推进时间，供时间相关判定稳定复现。
 */
public class ControlledTimeProvider implements TimeProvider {

    private LocalDateTime now;

    public ControlledTimeProvider(LocalDateTime initial) {
        this.now = initial;
    }

    public void setNow(LocalDateTime now) {
        this.now = now;
    }

    public void advanceMinutes(long minutes) {
        this.now = now.plusMinutes(minutes);
    }

    @Override
    public LocalDateTime now() {
        return now;
    }

    @Override
    public Clock clock() {
        return Clock.fixed(now.toInstant(ZoneOffset.ofHours(8)), ZONE);
    }
}
