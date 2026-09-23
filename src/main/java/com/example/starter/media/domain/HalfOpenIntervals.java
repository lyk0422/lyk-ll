package com.example.starter.media.domain;

import java.time.Instant;

/**
 * UTC 半开区间工具。区间为 [start, end)：端点相接（a.end == b.start）不算交集。
 */
public final class HalfOpenIntervals {

    private HalfOpenIntervals() {
    }

    /**
     * 两个半开区间是否存在正长度交集：max(start) &lt; min(end)。
     */
    public static boolean positiveOverlap(Instant startA, Instant endA, Instant startB, Instant endB) {
        return startA.isBefore(endB) && startB.isBefore(endA)
                && max(startA, startB).isBefore(min(endA, endB));
    }

    /**
     * 授权窗口 [windowStart, windowEnd) 是否完整覆盖时段 [slotStart, slotEnd)（端点可相接）。
     */
    public static boolean covers(Instant windowStart, Instant windowEnd, Instant slotStart, Instant slotEnd) {
        return !windowStart.isAfter(slotStart) && !windowEnd.isBefore(slotEnd);
    }

    /**
     * 时刻 at 是否落在半开窗口 [start, end) 内。
     */
    public static boolean contains(Instant start, Instant end, Instant at) {
        return !at.isBefore(start) && at.isBefore(end);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
