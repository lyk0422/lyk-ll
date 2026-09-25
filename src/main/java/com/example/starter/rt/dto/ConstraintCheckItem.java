package com.example.starter.rt.dto;

import java.math.BigDecimal;

/**
 * 单条约束逐项核对结果（可由模板限值与计划申报值复算）。
 *
 * @param organCode    危及器官编码
 * @param organName    危及器官名称
 * @param metricType   指标类型 DMAX_CGY / V_DOSE_PCT
 * @param metricRef    参照剂量(cGy)，DMAX_CGY 为空
 * @param plannedValue 计划申报值
 * @param limitValue   模板限值
 * @param withinLimit  是否未超限；恰等限值为 true
 */
public record ConstraintCheckItem(
        String organCode,
        String organName,
        String metricType,
        Long metricRef,
        BigDecimal plannedValue,
        BigDecimal limitValue,
        boolean withinLimit) {
}
