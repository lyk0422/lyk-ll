package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 状态时间线条目：由事件、巡检、评估与通告记录复算得出。
 *
 * @param type   条目类型（REPORTED/CLOSED_NOTAM/INSPECTION_STARTED/ESCALATED/ASSESSED/REOPENED/REOPEN_NOTAM/ARCHIVED/LATE_INSPECTION_RECORDED）
 * @param at     发生时间（UTC）
 * @param detail 说明
 */
public record TimelineEntry(
        String type,
        Instant at,
        String detail) {
}
