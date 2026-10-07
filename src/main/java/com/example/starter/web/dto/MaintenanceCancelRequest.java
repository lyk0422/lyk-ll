package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 检修单取消请求（仅未生效单可取消）。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望版本号，与当前版本不一致返回 409
 * @param operatorId      操作人标识
 */
public record MaintenanceCancelRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank String operatorId) {
}
