package com.example.starter.irrigation.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 轮次日界工具：每轮周期 7 天，按 UTC 日界对齐，1970-01-01T00:00:00Z 起算为第 0 轮。
 */
public final class TurnClock {

    /** 每轮周期天数（UTC 日界）。 */
    public static final long TURN_LENGTH_DAYS = 7L;

    private final Clock clock;

    public TurnClock(Clock clock) {
        this.clock = clock;
    }

    /** 默认使用系统 UTC 时钟。 */
    public static TurnClock systemUtc() {
        return new TurnClock(Clock.systemUTC());
    }

    /** 当前 UTC 时间所在轮次编号。 */
    public long currentTurnNo() {
        return turnNoAt(clock.instant());
    }

    /** 指定时刻所在轮次编号。 */
    public static long turnNoAt(Instant instant) {
        long epochDay = instant.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
        return Math.floorDiv(epochDay, TURN_LENGTH_DAYS);
    }

    /** 指定轮次的起始时刻（UTC）。 */
    public static Instant turnStart(long turnNo) {
        return Instant.ofEpochSecond(0).plus(turnNo * TURN_LENGTH_DAYS, java.time.temporal.ChronoUnit.DAYS);
    }
}
