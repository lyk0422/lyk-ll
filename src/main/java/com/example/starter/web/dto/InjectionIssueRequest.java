package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受控加注计划下达请求：先预占所需量，预占后可用量不得低于安全储备（恰等允许）。
 *
 * @param stationCode    门站编码
 * @param requestId      幂等键
 * @param requiredKg     预占所需量 kg 三位小数
 * @param expectedVersion 储罐期望版本号（当前版本不一致返回 409）
 * @param issuedAt       下达时刻（业务时间，Asia/Shanghai）
 */
public record InjectionIssueRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0.001", message = "预占量必须为正") BigDecimal requiredKg,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull LocalDateTime issuedAt) {
}
