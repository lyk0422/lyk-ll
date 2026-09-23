package com.example.starter.media.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 半开区间规则单元测试：端点相接合法，正长度交集才算冲突。
 */
class HalfOpenIntervalsTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-01T13:00:00Z");

    @Test
    void touchingEndpointsHaveNoPositiveOverlap() {
        assertFalse(HalfOpenIntervals.positiveOverlap(T0, T1, T1, T2));
        assertFalse(HalfOpenIntervals.positiveOverlap(T1, T2, T0, T1));
    }

    @Test
    void positiveLengthOverlapDetected() {
        assertTrue(HalfOpenIntervals.positiveOverlap(T0, T2, T1, T3));
        // 仅共享一个秒点（零长度）不算正长度交集
        assertFalse(HalfOpenIntervals.positiveOverlap(T0, T1, T1.minusSeconds(0), T1.plusSeconds(0)));
    }

    @Test
    void coversRequiresFullContainmentWithTouchingEndsAllowed() {
        assertTrue(HalfOpenIntervals.covers(T0, T2, T1, T2));
        assertTrue(HalfOpenIntervals.covers(T0, T2, T0, T1));
        assertTrue(HalfOpenIntervals.covers(T0, T2, T0, T2));
        assertFalse(HalfOpenIntervals.covers(T0, T2, T1, T3));
        // 授权窗口在端点外溢出一秒即不满足整段覆盖
        assertFalse(HalfOpenIntervals.covers(T0, T2, T0, T2.plusSeconds(1)));
    }

    @Test
    void containsIsHalfOpen() {
        assertTrue(HalfOpenIntervals.contains(T0, T2, T0));
        assertTrue(HalfOpenIntervals.contains(T0, T2, T1));
        assertFalse(HalfOpenIntervals.contains(T0, T2, T2));
        assertFalse(HalfOpenIntervals.contains(T0, T2, T0.minusSeconds(1)));
    }
}
