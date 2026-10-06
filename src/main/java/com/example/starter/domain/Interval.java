package com.example.starter.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 半开区间 [start, end)：端点相接不冲突，内部重叠冲突。
 *
 * @param start 区间开始（含）
 * @param end   区间结束（不含）
 */
public record Interval(LocalDateTime start, LocalDateTime end) {

    public Interval {
        if (start == null || end == null) {
            throw new IllegalArgumentException("区间端点不能为空");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("区间结束必须晚于开始");
        }
    }

    /**
     * 半开区间重叠判定：[s1,e1) 与 [s2,e2) 内部重叠才算冲突，端点相接（e1==s2 或 e2==s1）不冲突。
     */
    public boolean overlaps(Interval other) {
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }

    /** 区间时长。 */
    public Duration duration() {
        return Duration.between(start, end);
    }
}
