package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 限功率指令执行确认请求：上报实际功率（单位 W），恰等限值合法、超过拒绝确认。
 */
public record ConfirmExecutionRequest(
        @NotNull Integer actualPowerWatts,
        @NotBlank String confirmedBy) {
}
