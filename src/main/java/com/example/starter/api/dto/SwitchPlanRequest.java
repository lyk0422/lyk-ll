package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 方案切换请求：先停旧方案、后启新方案，两步同一事务。
 */
public record SwitchPlanRequest(
        @NotBlank String requestId,
        @NotBlank String kilnId,
        @NotBlank String newPlanNo) {
}
