package com.example.starter.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤回来源召回请求。requestId 为幂等键；expectedVersion 为乐观锁版本，过期返回 409。
 * 须两名不同质检员持相互独立的凭据共同确认。
 */
public record WithdrawRecallRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotBlank String firstInspector,
        @NotBlank String firstCredential,
        @NotBlank String secondInspector,
        @NotBlank String secondCredential) {
}
