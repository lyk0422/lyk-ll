package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 恢复开放响应。
 *
 * @param eventId   事件ID
 * @param status    事件状态（REOPENED）
 * @param reopenedAt 恢复开放时间（UTC）
 * @param notamNo   恢复通告编号（合成），与开放同一事务发布
 */
public record ReopenResponse(
        long eventId,
        String status,
        Instant reopenedAt,
        String notamNo) {
}
