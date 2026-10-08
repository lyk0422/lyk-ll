package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 检修单取消请求：仅未生效检修单可取消，expectedVersion 不一致返回 409。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的当前版本
 */
public record MaintenanceCancelRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Integer expectedVersion) {
}
