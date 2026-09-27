package com.example.starter.metro.dto.refund;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调账单纳入开放调账批次请求。
 *
 * @param requestId       幂等键
 * @param adjustmentNo    调账单号
 * @param expectedVersion 调账单期望版本（已登记为 1，已纳入为 2）
 */
public record AdjustmentIncludeRequest(
        @NotBlank String requestId,
        @NotBlank String adjustmentNo,
        @NotNull @Positive Integer expectedVersion) {
}
