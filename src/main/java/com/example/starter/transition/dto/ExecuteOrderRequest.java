package com.example.starter.transition.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 改品种执行请求：关键参数须落在新品种工艺窗口内（含边界）。
 *
 * @param machineSpeedMMin   执行车速，单位 m/min
 * @param stockConsistencyPct 执行上料浓度，单位 %
 * @param switchTimeSec      切换时间，单位 秒，须为正数
 */
public record ExecuteOrderRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal machineSpeedMMin,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal stockConsistencyPct,
        @NotNull Integer switchTimeSec) {
}
