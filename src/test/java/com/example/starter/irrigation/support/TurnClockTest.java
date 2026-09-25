package com.example.starter.irrigation.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TurnClockTest {

    @Test
    void turnsAreSevenDaysAlignedToUtcEpoch() {
        // 1970-01-01T00:00:00Z 为第 0 轮起点
        assertThat(TurnClock.turnNoAt(Instant.parse("1970-01-01T00:00:00Z"))).isZero();
        // 1 月 1 日至 7 日（epoch day 0-6）为第 0 轮
        assertThat(TurnClock.turnNoAt(Instant.parse("1970-01-07T23:59:59Z"))).isZero();
        // 1 月 8 日 00:00 UTC 进入第 1 轮
        assertThat(TurnClock.turnNoAt(Instant.parse("1970-01-08T00:00:00Z"))).isEqualTo(1L);
        // UTC 日界：1970-01-14T23:59:59Z 仍为第 1 轮，次日进入第 2 轮
        assertThat(TurnClock.turnNoAt(Instant.parse("1970-01-14T23:59:59Z"))).isEqualTo(1L);
        assertThat(TurnClock.turnNoAt(Instant.parse("1970-01-15T00:00:00Z"))).isEqualTo(2L);
    }

    @Test
    void turnStartIsReversible() {
        long turnNo = TurnClock.turnNoAt(Instant.parse("2026-09-25T03:15:00Z"));
        Instant start = TurnClock.turnStart(turnNo);
        assertThat(TurnClock.turnNoAt(start)).isEqualTo(turnNo);
        assertThat(TurnClock.turnNoAt(start.plusSeconds(7L * 24 * 3600 - 1))).isEqualTo(turnNo);
    }

    @Test
    void currentTurnNoUsesProvidedClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-25T03:15:00Z"), ZoneOffset.UTC);
        long expected = TurnClock.turnNoAt(Instant.parse("2026-09-25T03:15:00Z"));
        assertThat(new TurnClock(fixed).currentTurnNo()).isEqualTo(expected);
    }
}
