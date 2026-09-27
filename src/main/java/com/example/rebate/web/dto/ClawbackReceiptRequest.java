package com.example.rebate.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 追缴到账回执登记请求：仅已批准冲回单可登记，允许多笔部分到账。
 *
 * @param receiptSerial 追缴回执独立流水号；同流水异金额拒绝、同流水同金额去重
 * @param amountCents 本笔实际收回金额（正数整数分），累计不超批准金额
 * @param expectedVersion 期望冲回单版本（批准后版本为 2，之后每笔回执 +1）
 * @param occurredAt 业务精确时刻（UTC，纳入幂等指纹）
 */
public record ClawbackReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String receiptSerial,
        @NotNull @Min(1) Long amountCents,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull Instant occurredAt) {
}
