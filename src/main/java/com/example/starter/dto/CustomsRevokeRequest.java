package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 通关单撤销请求（requestId 幂等）。撤销释放全部未签发子批预占并阻断后续签发，已签发证据不变。
 *
 * @param requestId       幂等键
 * @param customsNo       待撤销的合成通关单号
 * @param expectedVersion 调用方期望的通关单台账版本，与当前版本不一致返回 409
 * @param at              本次操作精确时刻（纳入幂等指纹）
 */
public record CustomsRevokeRequest(
        @NotBlank String requestId,
        @NotBlank String customsNo,
        @Positive int expectedVersion,
        @NotNull Instant at
) {
}
