package com.example.starter.dispatch.api;

import com.example.starter.dispatch.domain.GateStepStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单个闸门执行明细视图。
 *
 * @param seq           令中执行顺序（从 1 开始）
 * @param gateCode      闸门编号
 * @param targetOpening 目标开度（百分比）
 * @param actualOpening 实际开度（百分比），未回执为 null
 * @param deviation     相对偏差（|实际-目标|/目标），未回执为 null
 * @param inPosition    是否到位（偏差不超过 ±2%，恰在边界判到位），未回执为 null
 * @param completedAt   实际完成时刻（UTC），未回执为 null
 * @param status        执行项状态
 */
public record GateStepView(
        int seq,
        String gateCode,
        BigDecimal targetOpening,
        BigDecimal actualOpening,
        BigDecimal deviation,
        Boolean inPosition,
        Instant completedAt,
        GateStepStatus status) {
}
