package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调账批次封存请求：封存为终态，封存后只读。
 *
 * @param requestId       幂等键
 * @param batchNo         调账批次号
 * @param sealTime        封存精确时刻（Asia/Shanghai）
 * @param expectedVersion 批次期望版本
 */
public record AdjustmentSealRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull LocalDateTime sealTime,
        @Positive @NotNull Integer expectedVersion) {
}
