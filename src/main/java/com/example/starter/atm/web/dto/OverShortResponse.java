package com.example.starter.atm.web.dto;

/**
 * 长短款挂账视图。amount=实际-计划（正长款、负短款），单位：分。
 */
public record OverShortResponse(
        Long id,
        String planNo,
        String direction,
        long amount,
        long plannedAmount,
        long actualAmount,
        String status,
        long occurredAt,
        String deadlineUtcDate,
        Long settledAt,
        Long escalatedAt
) {
}
