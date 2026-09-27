package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调整单纳入开放调账批次请求：一张调整单只能纳入一个批次，
 * 重复纳入幂等成功但不重复计额；批次封存后拒绝纳入。
 *
 * @param requestId       幂等键
 * @param adjustmentNo    调整单号
 * @param batchNo         调账批次号
 * @param includeTime     纳入精确时刻（Asia/Shanghai）
 * @param expectedVersion 调账批次期望版本
 */
public record AdjustmentIncludeRequest(
        @NotBlank String requestId,
        @NotBlank String adjustmentNo,
        @NotBlank String batchNo,
        @NotNull LocalDateTime includeTime,
        @Positive @NotNull Integer expectedVersion) {
}
