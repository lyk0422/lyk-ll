package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 救援分段清空确认请求。
 *
 * @param requestId   幂等请求标识
 * @param confirmerId 确认人标识
 */
public record ClearanceRequest(
        @NotBlank String requestId,
        @NotBlank String confirmerId) {
}
