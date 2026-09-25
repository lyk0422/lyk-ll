package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 告警确认请求，requestId 为幂等键，指纹覆盖 requestId 与 alertId。
 */
public record AlertAckRequest(
        @NotNull @Positive Long alertId,
        @NotBlank(message = "不能为空") String requestId) {
}
