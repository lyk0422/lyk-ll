package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行巡检检查项行：对应 routine_check_item 表。
 *
 * @param id          检查项主键
 * @param planId      所属例行计划ID
 * @param itemCode    检查项编码（合成）
 * @param itemText    检查项文本（合法文本可含任意分隔符）
 * @param passed      该项检查是否通过
 * @param completedAt 检查项完成时刻（UTC）
 * @param late        是否迟到回报：true=计划被取消/中止后回报，仅登记不开放跑道
 * @param requestId   检查回报幂等请求标识
 * @param createdAt   记录创建时间（UTC）
 */
public record RoutineCheckItem(
        long id,
        long planId,
        String itemCode,
        String itemText,
        boolean passed,
        Instant completedAt,
        boolean late,
        String requestId,
        Instant createdAt) {
}
