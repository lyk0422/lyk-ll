package com.example.starter.metro.dto.refund;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调账批次封存请求：封存后批次只读，重复封存幂等成功。
 *
 * @param requestId       幂等键
 * @param batchNo         调账批次号
 * @param expectedVersion 批次期望版本（开放批次为 1）
 */
public record AdjustmentSealRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull @Positive Integer expectedVersion) {
}
