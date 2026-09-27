package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 通关单修订请求：仅允许增加未占余量（提高可核销总数），不得修改历史核销、企业与品种。
 *
 * @param requestId        幂等键
 * @param declarationNo    合成通关单号
 * @param newTotalQuantity 修订后可核销总数量（正整数，单位：支；必须大于当前总数）
 * @param expectedVersion  调用方持有的通关单版本，与当前版本不符整笔拒绝
 * @param occurredAt       客户端精确请求时刻（UTC，纳入幂等指纹）
 */
public record CustomsAmendRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @Positive @NotNull Integer newTotalQuantity,
        @NotNull Integer expectedVersion,
        @NotNull Instant occurredAt
) {
}
