package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 封锁申请领域模型（含区段与版本）。所有时间为 UTC；占用窗为半开区间
 * {@code [bufferedStart, bufferedEnd)}，端点相接不冲突。
 */
public record PossessionRequest(
        String id,
        List<String> sectionIds,
        Instant start,
        Instant end,
        Instant bufferedStart,
        Instant bufferedEnd,
        long preBufferSeconds,
        long postBufferSeconds,
        int priority,
        RequestStatus status,
        int version,
        Instant businessTime,
        Instant createdAt
) {
}
