package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调账批次封存请求：封存后只读。
 *
 * @param requestId       幂等键
 * @param expectedVersion 开放调账批次期望版本（乐观锁）
 * @param sealTime        封存精确时刻（Asia/Shanghai），参与结构化指纹
 */
public record AdjustSealRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull LocalDateTime sealTime) {
}
