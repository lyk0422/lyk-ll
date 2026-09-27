package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 签发前撤回包装子批请求：释放本子批在通关单上的预占（仅释放一次），子批流程转撤回终态。
 *
 * @param requestId       幂等键
 * @param batchNo         包装子批号
 * @param expectedVersion 调用方持有的预占绑定版本，与当前版本不符整笔拒绝
 * @param occurredAt      客户端精确请求时刻（UTC，纳入幂等指纹）
 */
public record CustomsWithdrawRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull Integer expectedVersion,
        @NotNull Instant occurredAt
) {
}
