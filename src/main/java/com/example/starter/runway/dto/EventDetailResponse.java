package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 事件详情：含状态时间线（可复算）。
 *
 * @param eventId            事件ID
 * @param runwayId           跑道编号
 * @param eventType          事件类型
 * @param status             当前状态
 * @param reportedAt         接报时间（UTC）
 * @param closedAt           临时关闭时间（UTC）
 * @param inspectionDeadline 巡检开始截止时刻（UTC）
 * @param escalated          是否已升级
 * @param timeline           状态时间线
 */
public record EventDetailResponse(
        long eventId,
        String runwayId,
        String eventType,
        String status,
        Instant reportedAt,
        Instant closedAt,
        Instant inspectionDeadline,
        boolean escalated,
        List<TimelineEntry> timeline) {
}
