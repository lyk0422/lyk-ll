package com.example.starter.slaughter.dto;

import java.time.OffsetDateTime;

/**
 * 出厂核销响应。
 *
 * @param batchNo         批次编号
 * @param status          批次状态（CHECKED_OUT 终态）
 * @param checkedOutAtUtc 核销时刻（UTC）
 */
public record CheckoutResponse(
        String batchNo,
        String status,
        OffsetDateTime checkedOutAtUtc) {
}
