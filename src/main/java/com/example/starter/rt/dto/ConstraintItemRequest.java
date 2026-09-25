package com.example.starter.rt.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.example.starter.rt.domain.MetricType;

/**
 * 约束模板明细请求：一个危及器官的一项剂量体积限值。
 *
 * @param organCode  危及器官编码
 * @param organName  危及器官名称
 * @param metricType 指标类型 DMAX_CGY / V_DOSE_PCT
 * @param metricRef  参照剂量(cGy)，仅 V_DOSE_PCT 使用，其余为 null
 * @param limitValue 限值（恰等合法），DMAX_CGY 单位 cGy，V_DOSE_PCT 单位 %
 */
public record ConstraintItemRequest(
        @NotBlank(message = "不能为空") String organCode,
        @NotBlank(message = "不能为空") String organName,
        @NotNull(message = "不能为空") MetricType metricType,
        @PositiveOrZero(message = "须为非负整数") Long metricRef,
        @NotNull(message = "不能为空") @PositiveOrZero(message = "须非负") BigDecimal limitValue) {
}
