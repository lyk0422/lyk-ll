package com.example.starter.rail.service;

import java.time.Instant;

/**
 * UTC epoch 毫秒与 ISO-8601 时刻转换工具。
 */
public final class InstantUtil {

    private InstantUtil() {
    }

    public static String toString(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }
}
