package com.example.starter.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * 半开区间 [start, end) 冲突判定：端点相接不冲突，内部重叠冲突。
 */
class IntervalTest {

    private static Interval iv(int startHour, int endHour) {
        return new Interval(
                LocalDateTime.of(2026, 10, 6, startHour, 0),
                LocalDateTime.of(2026, 10, 6, endHour, 0));
    }

    @Test
    void 端点相接不冲突() {
        assertFalse(iv(10, 12).overlaps(iv(12, 14)), "前区间结束等于后区间开始，不冲突");
        assertFalse(iv(12, 14).overlaps(iv(10, 12)), "反向端点相接同样不冲突");
    }

    @Test
    void 内部重叠冲突() {
        assertTrue(iv(10, 12).overlaps(iv(11, 13)));
        assertTrue(iv(11, 13).overlaps(iv(10, 12)));
        assertTrue(iv(10, 12).overlaps(iv(10, 12)), "完全重合冲突");
        assertTrue(iv(10, 14).overlaps(iv(11, 12)), "包含关系冲突");
    }

    @Test
    void 完全分离不冲突() {
        assertFalse(iv(8, 10).overlaps(iv(12, 14)));
    }

    @Test
    void 非法区间被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> iv(10, 10), "零长度区间非法");
        assertThrows(IllegalArgumentException.class, () -> iv(12, 10), "结束早于开始非法");
        assertThrows(IllegalArgumentException.class, () -> new Interval(null, LocalDateTime.now()));
    }
}
