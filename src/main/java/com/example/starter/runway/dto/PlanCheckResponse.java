package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 检查项回报结果。
 *
 * @param itemId          检查项ID
 * @param planId          计划ID
 * @param status          回报后计划状态
 * @param version         回报后计划聚合版本（检查回报保存检查项，计划版本加1）
 * @param itemCode        检查项编码
 * @param result          检查结果
 * @param observedAt      观测时刻（UTC）
 */
public record PlanCheckResponse(
        long itemId,
        long planId,
        String status,
        int version,
        String itemCode,
        String result,
        Instant observedAt) {
}
