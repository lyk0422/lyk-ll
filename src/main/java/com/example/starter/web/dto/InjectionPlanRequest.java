package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受控加注计划请求：先预占所需量，预占后可用量不得低于安全储备（恰等允许）。
 *
 * @param stationCode    门站编码
 * @param requestId      幂等键
 * @param plannedKg      计划预占量 kg，三位小数
 * @param plannedAt      计划时刻（业务时间）
 * @param expectedVersion 期望的储罐版本，须与当前版本一致
 */
public record InjectionPlanRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal plannedKg,
        @NotNull LocalDateTime plannedAt,
        @NotNull Integer expectedVersion) {
}
