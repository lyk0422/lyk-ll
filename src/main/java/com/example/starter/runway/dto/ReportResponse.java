package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 接报响应：事件已临时关闭并同步发布关闭通告。
 *
 * @param eventId            事件ID
 * @param runwayId           跑道编号
 * @param status             事件状态（CLOSED）
 * @param closedAt           临时关闭生效时间（UTC）
 * @param inspectionDeadline 巡检开始截止时刻（关闭后15分钟，恰满即迟延）
 * @param notamNo            关闭通告编号（合成）
 */
public record ReportResponse(
        long eventId,
        String runwayId,
        String status,
        Instant closedAt,
        Instant inspectionDeadline,
        String notamNo) {
}
