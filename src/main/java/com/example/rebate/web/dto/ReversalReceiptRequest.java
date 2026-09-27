package com.example.rebate.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * 追缴到账回执登记请求：独立流水号＋实际收回金额（正整数分）＋精确到账时刻。
 *
 * @param expectedVersion 冲回单当前版本（成功后加 1）
 * @param receiptSerial 追缴回执独立流水号，同冲回单内唯一；同流水异金额拒绝
 * @param amountCents 本笔实际收回金额（正整数分），累计不超过批准金额
 * @param occurredAt 回执载明的精确到账时刻(UTC)，纳入幂等指纹
 */
public record ReversalReceiptRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank String receiptSerial,
        @NotNull @Positive Long amountCents,
        @NotNull Instant occurredAt) {
}
