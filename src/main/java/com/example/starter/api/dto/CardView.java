package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 工序卡视图。
 */
public record CardView(
        String cardNo,
        String station,
        String productSn,
        String witnessPoint,
        String status,
        String operator,
        Instant dispatchedAt,
        Instant quarantinedAt,
        Instant archivedAt) {
}
