package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.ClusterStatus;

import java.time.Instant;

/**
 * 聚集事件列表项（摘要，不含卡号时间线）。
 */
public record ClusterEventSummary(
        long id,
        String siteCode,
        ClusterStatus status,
        Instant firstCaseAt,
        Instant lastCaseAt,
        int cardCount,
        Instant createdAt,
        Instant closedAt) {
}
