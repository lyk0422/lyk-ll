package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 每日（UTC 日界）能耗预算分配请求，单位 kWh。
 */
public record BudgetAllocateRequest(
        @NotBlank(message = "不能为空") String zoneCode,
        @NotBlank(message = "不能为空") String budgetDay,
        @NotNull @DecimalMin("0.000") BigDecimal budgetKwh) {
}
