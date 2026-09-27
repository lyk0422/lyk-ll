package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 包装子批绑定通关单请求（requestId 幂等）。绑定即按申报支数预占，累计不超总数。
 *
 * @param requestId        幂等键
 * @param customsNo        所绑定的合成通关单号
 * @param batchNo          包装子批评号（每个子批只能绑定一张通关单）
 * @param declaredQuantity 本子批申报预占支数（正整数，单位：支）
 * @param expectedVersion  调用方期望的通关单台账版本，与当前版本不一致返回 409
 * @param at               本次操作精确时刻（纳入幂等指纹）
 */
public record CustomsBindRequest(
        @NotBlank String requestId,
        @NotBlank String customsNo,
        @NotBlank String batchNo,
        @Positive int declaredQuantity,
        @Positive int expectedVersion,
        @NotNull Instant at
) {
}
