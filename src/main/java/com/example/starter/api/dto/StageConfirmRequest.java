package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 升温阶段确认请求（以 requestId 幂等）。阶段确认须两名不同操作人复核，单人确认不生效。
 *
 * @param requestId   确认幂等键
 * @param operator    操作人工号
 * @param currentTemp 确认时实测温度（℃），恰等于目标判达标
 */
public record StageConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull BigDecimal currentTemp) {
}
