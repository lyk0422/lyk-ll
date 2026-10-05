package com.example.starter.fishery.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 跨船配额调剂对方确认请求。
 *
 * @param requestId 幂等键（指纹覆盖 requestId 与调剂单号）
 */
public record TransferConfirmRequest(
        @NotBlank String requestId) {
}
