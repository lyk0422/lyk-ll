package com.example.starter.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤回来源召回请求。expectedVersion 为乐观锁版本，过期返回 409；
 * 须两名不同质检员确认且使用相互独立的凭据。
 */
public record WithdrawRecallRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String inspectorPrimary,
        @NotBlank String inspectorSecondary,
        @NotBlank String credentialPrimary,
        @NotBlank String credentialSecondary) {
}
