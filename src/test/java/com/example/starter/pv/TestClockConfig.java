package com.example.starter.pv;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控 UTC 时钟：通过 {@link #HOLDER} 在场景间切换固定时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 当前测试持有的时钟，默认固定在 2026-02-01T10:00:00Z。 */
    public static final AtomicReference<Clock> HOLDER = new AtomicReference<>(
            Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"), ZoneOffset.UTC));

    /**
     * 重置为默认固定时钟。
     */
    public static void reset() {
        HOLDER.set(Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * 固定到指定时刻（UTC）。
     */
    public static void fixAt(String instantUtc) {
        HOLDER.set(Clock.fixed(Instant.parse(instantUtc), ZoneOffset.UTC));
    }

    /**
     * 在当前时钟基础上前进指定时长。
     */
    public static void advance(Duration duration) {
        HOLDER.set(Clock.offset(HOLDER.get(), duration));
    }

    /**
     * 始终委托给 HOLDER 的时钟 Bean，替换生产系统时钟。
     */
    @Bean
    @Primary
    public Clock controlledClock() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return HOLDER.get().instant();
            }
        };
    }
}
