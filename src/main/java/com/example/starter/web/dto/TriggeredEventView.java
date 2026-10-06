package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 触发的管制事件视图。
 *
 * @param eventId     事件 id
 * @param type        事件类型
 * @param status      事件状态
 * @param segmentId   触发分段 id
 * @param avgSpeed    触发时 10 分钟平均风速（m/s）
 * @param triggeredAt 触发判定对应的测量时刻（UTC）
 */
public record TriggeredEventView(long eventId, String type, String status, long segmentId,
                                 double avgSpeed, Instant triggeredAt) {
}
