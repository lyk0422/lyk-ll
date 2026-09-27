package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受控加注计划请求：先预占所需量，预占后可用量不得低于安全储备（恰等允许）。
 *
 * @param stationCode     门站编码
 * @param requestId       幂等键（同时作为计划单号，回执/取消按此引用）
 * @param expectedVersion 期望的储罐当前版本号，不一致返回 409
 * @param requiredKg      计划预占量 kg（三位小数，须为正）
 * @param plannedAt       计划下达精确时刻（业务时间，Asia/Shanghai）
 */
public record InjectionPlanRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal requiredKg,
        @NotNull LocalDateTime plannedAt) {
}
