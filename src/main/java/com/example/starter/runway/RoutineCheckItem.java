package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行巡检检查项行：对应 routine_check_item 表。
 *
 * @param id            检查项主键
 * @param planId        所属计划ID
 * @param itemCode      检查项编码（计划内唯一，合法文本允许包含分隔符）
 * @param seqNo         发布集合排序后的稳定序号
 * @param completedAt   完成时刻（UTC），未回报为 null
 * @param recordedLate  是否中止后迟到回报
 * @param requestId     最近一次回报幂等请求标识
 * @param createdAt     创建时间（UTC）
 * @param updatedAt     更新时间（UTC）
 */
public record RoutineCheckItem(
        long id,
        long planId,
        String itemCode,
        int seqNo,
        Instant completedAt,
        boolean recordedLate,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
