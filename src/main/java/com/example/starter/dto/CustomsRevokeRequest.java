package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 通关单撤销请求：撤销后全部未签发子批预占释放并阻断签发，已签发证据保留并记风险事件。
 *
 * @param requestId       幂等键
 * @param declarationNo   合成通关单号
 * @param expectedVersion 调用方持有的通关单版本，与当前版本不符整笔拒绝
 * @param occurredAt      客户端精确请求时刻（UTC，纳入幂等指纹）
 */
public record CustomsRevokeRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotNull Integer expectedVersion,
        @NotNull Instant occurredAt
) {
}
