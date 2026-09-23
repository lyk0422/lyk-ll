package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 封锁申请视图。
 */
public record RequestView(
        String requestId,
        List<String> sectionIds,
        Instant start,
        Instant end,
        Instant bufferedStart,
        Instant bufferedEnd,
        long preBufferSeconds,
        long postBufferSeconds,
        int priority,
        String status,
        int version,
        Instant businessTime
) {
}
