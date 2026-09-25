package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单名潜水员的一段水下记录。两人成组：同一潜次、同一相位与起止时刻、
 * 两名潜水员各提交一条（requestId 各自不同），两条齐备前记录不生效（PENDING）。
 * 时刻均为 UTC 纪元秒。
 */
public record SegmentRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String operationNo,
        @NotBlank @Size(max = 64) String diverId,
        @NotBlank @Size(max = 16) String phase,
        @NotNull Integer startDepthM,
        @NotNull Integer endDepthM,
        @NotNull Long startedAt,
        @NotNull Long endedAt) {
}
