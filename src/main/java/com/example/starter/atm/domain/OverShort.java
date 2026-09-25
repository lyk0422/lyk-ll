package com.example.starter.atm.domain;

/**
 * 长短款挂账链记录。amount = 实际（设备入账数）-计划，正为长款 LONG，负为短款 SHORT，单位：分。
 * 须在发生日起 3 个自然日内（UTC 日界，恰第 3 日受理合法）销账，逾期升级并冻结责任人。
 */
public record OverShort(
        Long id,
        long planId,
        String direction,
        long amount,
        long plannedAmount,
        long actualAmount,
        String status,
        long occurredAt,
        String deadlineUtcDate,
        Long settledAt,
        String settleRequestId,
        Long escalatedAt,
        long createdAt,
        long updatedAt
) {
}
