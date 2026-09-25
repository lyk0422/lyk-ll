package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 派单请求。
 *
 * @param requestId   幂等请求号
 * @param workOrderId 工单ID
 * @param teamId      目标班组ID
 */
public record DispatchRequest(
        @NotBlank String requestId,
        @NotNull Long workOrderId,
        @NotNull Long teamId) {
}
