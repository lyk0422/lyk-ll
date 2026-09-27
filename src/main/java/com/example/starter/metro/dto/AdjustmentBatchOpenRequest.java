package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 开放调账批次请求。
 *
 * @param requestId 幂等键
 * @param batchNo   调账批次号，全局唯一
 * @param openTime  开放精确时刻（Asia/Shanghai）
 */
public record AdjustmentBatchOpenRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull LocalDateTime openTime) {
}
