package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 检查项视图：用于计划详情中的已保存检查项列表。
 *
 * @param itemId     检查项ID
 * @param itemCode   检查项编码
 * @param result     检查结果
 * @param remark     备注（可空）
 * @param observedAt 观测时刻（UTC）
 */
public record PlanCheckItemView(
        long itemId,
        String itemCode,
        String result,
        String remark,
        Instant observedAt) {
}
