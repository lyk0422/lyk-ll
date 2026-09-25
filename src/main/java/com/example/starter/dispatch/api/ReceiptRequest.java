package com.example.starter.dispatch.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单门执行回执请求。
 *
 * @param gateCode      回执闸门编号
 * @param actualOpening 实际开度（百分比，0-100）
 * @param completedAt   实际完成时刻（UTC）
 */
public record ReceiptRequest(
        @NotBlank String gateCode,
        @NotNull BigDecimal actualOpening,
        @NotNull Instant completedAt) {
}
