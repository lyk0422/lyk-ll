package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 加注执行回执请求：预占转实际消耗并释放未用部分，实际量不得大于预占。
 *
 * @param requestId       幂等键
 * @param planId          加注计划ID
 * @param actualKg        实际消耗 kg，0≤实际≤预占
 * @param executedAt      执行时刻（业务时间）
 * @param expectedVersion 期望的储罐版本
 */
public record InjectionReceiptRequest(
        @NotBlank String requestId,
        @NotNull Long planId,
        @NotNull @DecimalMin("0") BigDecimal actualKg,
        @NotNull LocalDateTime executedAt,
        @NotNull Integer expectedVersion) {
}
