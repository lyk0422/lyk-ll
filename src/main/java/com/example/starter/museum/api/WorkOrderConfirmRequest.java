package com.example.starter.museum.api;

import jakarta.validation.constraints.NotBlank;

/** 保护工单处置确认：须在异常触发后 24 小时内（恰满 24h 未确认即升级紧急）。 */
public record WorkOrderConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo,
        @NotBlank String handler,
        String note
) {
}
