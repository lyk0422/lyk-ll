package com.example.starter.torque.dto;

import java.time.Instant;

/**
 * 工序卡视图。
 */
public record CardView(
        String cardNo,
        String station,
        String productNo,
        String status,
        int currentRework,
        Instant dispatchedAt,
        Instant releasedAt,
        Instant quarantinedAt
) {
}
