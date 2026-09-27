package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * 追缴到账回执请求：独立流水号、实际收回金额与精确业务时刻。
 *
 * @param expectedVersion 追缴明细期望版本（从 1 开始，每笔成功回执后加 1）
 * @param receiptSerial 追缴回执独立流水号（全局唯一；同流水异金额拒绝）
 * @param receivedCents 本笔实际收回金额（正数整数分，可多笔部分到账）
 * @param receivedAt 回执业务精确时刻（UTC，ISO-8601，含时区），纳入指纹
 */
public record ClawbackReceiptRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String receiptSerial,
        @NotNull @Positive Long receivedCents,
        @NotNull Instant receivedAt) {
}
