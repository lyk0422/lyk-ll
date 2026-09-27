package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * 追缴回执登记请求。
 *
 * @param serialNo 追缴回执独立流水号，全局唯一；同流水异金额拒绝
 * @param amountCents 本笔实际收回金额，单位：分，正整数
 * @param receivedAt 到账精确时刻（UTC），纳入幂等指纹；乱序到账不改变累计总额
 * @param expectedVersion 冲回单当前期望版本，收齐成功后冲回单版本加 1；不匹配返回 409
 */
public record ClawbackReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String serialNo,
        @NotNull @Positive Long amountCents,
        @NotNull Instant receivedAt,
        @NotNull @Positive Integer expectedVersion) {
}
