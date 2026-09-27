package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 签发前撤回包装子批请求（requestId 幂等）。撤回释放本子批预占且只释放一次；已核销（已签发）不允许撤回。
 *
 * @param requestId       幂等键
 * @param batchNo         待撤回的包装子批评号
 * @param expectedVersion 调用方期望的子批绑定版本，与当前版本不一致返回 409
 * @param at              本次操作精确时刻（纳入幂等指纹）
 */
public record SubbatchWithdrawRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @Positive int expectedVersion,
        @NotNull Instant at
) {
}
