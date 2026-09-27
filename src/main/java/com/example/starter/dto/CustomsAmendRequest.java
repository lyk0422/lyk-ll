package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 通关单修订请求（requestId 幂等）。仅允许增加可核销总支数（增加未占余量），
 * 不改变历史核销，也不改变企业、品种。
 *
 * @param requestId       幂等键
 * @param customsNo       待修订的合成通关单号
 * @param newTotalQuantity 修订后的总支数，必须大于当前总支数（单位：支）
 * @param expectedVersion 调用方期望的通关单台账版本，与当前版本不一致返回 409
 * @param at              本次操作精确时刻（纳入幂等指纹）
 */
public record CustomsAmendRequest(
        @NotBlank String requestId,
        @NotBlank String customsNo,
        @Positive int newTotalQuantity,
        @Positive int expectedVersion,
        @NotNull Instant at
) {
}
