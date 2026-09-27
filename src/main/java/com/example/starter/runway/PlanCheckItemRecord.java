package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行巡检检查项回报行：对应 plan_check_item 表。
 *
 * @param id         检查项主键
 * @param planId     所属例行计划ID
 * @param itemCode   检查项编码
 * @param result     检查结果：PASS/FAIL
 * @param remark     备注（可空，允许含任意分隔符的合法文本）
 * @param observedAt 现场观测精确时刻（UTC）
 * @param requestId  检查回报幂等请求标识
 * @param createdAt  记录创建时间（UTC）
 */
public record PlanCheckItemRecord(
        long id,
        long planId,
        String itemCode,
        CheckResult result,
        String remark,
        Instant observedAt,
        String requestId,
        Instant createdAt) {
}
