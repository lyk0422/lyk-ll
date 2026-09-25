package com.example.starter.rt.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.example.starter.rt.domain.MetricType;

/**
 * 计划申报的一项危及器官剂量体积数据。
 *
 * @param organCode   危及器官编码，须与约束模板明细一一对应
 * @param metricType  指标类型 DMAX_CGY / V_DOSE_PCT
 * @param metricRef   参照剂量(cGy)，仅 V_DOSE_PCT 使用
 * @param metricValue 申报值，DMAX_CGY 单位 cGy，V_DOSE_PCT 单位 %
 */
public record OarDoseRequest(
        @NotBlank(message = "不能为空") String organCode,
        @NotNull(message = "不能为空") MetricType metricType,
        @PositiveOrZero(message = "须为非负整数") Long metricRef,
        @NotNull(message = "不能为空") @PositiveOrZero(message = "须非负") BigDecimal metricValue) {
}
