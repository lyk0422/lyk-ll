package com.example.starter.runway;

import java.time.Instant;

/**
 * 跑道关闭事件行：对应 runway_event 表。
 * 状态流转 CLOSED→INSPECTING→ASSESSED→REOPENED/持续关闭→ARCHIVED（终态）。
 *
 * @param id                 事件主键
 * @param runwayId           跑道编号（合成数据）
 * @param eventType          事件类型
 * @param status             当前状态
 * @param reportedAt         接报时间（UTC）
 * @param closedAt           临时关闭生效时间（UTC）
 * @param inspectionDeadline 巡检开始截止时刻（closed_at+15分钟，恰满即迟延）
 * @param escalated          是否已升级
 * @param assessmentType     最近一次评估类型，未评估为 null
 * @param requestId          接报幂等请求标识
 * @param createdAt          记录创建时间（UTC）
 * @param updatedAt          记录最近更新时间（UTC）
 */
public record RunwayEvent(
        long id,
        String runwayId,
        EventType eventType,
        EventStatus status,
        Instant reportedAt,
        Instant closedAt,
        Instant inspectionDeadline,
        boolean escalated,
        AssessmentType assessmentType,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
