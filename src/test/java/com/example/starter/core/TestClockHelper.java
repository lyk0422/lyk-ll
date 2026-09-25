package com.example.starter.core;

import java.time.LocalDateTime;

/**
 * 测试用自然小时起点构造辅助（Asia/Shanghai，与可控时钟同日）。
 */
public final class TestClockHelper {

    private TestClockHelper() {
    }

    public static LocalDateTime hour(int hour) {
        return LocalDateTime.of(2026, 9, 25, hour, 0, 0);
    }
}
