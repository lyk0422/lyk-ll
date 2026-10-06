package com.example.starter.model;

import java.time.Instant;

/**
 * 管制事件行记录。
 *
 * @param id               事件 id
 * @param lineId           线路 id
 * @param type             事件类型
 * @param status           事件状态
 * @param triggerSegmentId 触发分段 id
 * @param avgSpeed         触发时 10 分钟平均风速（m/s）
 * @param triggeredAt      触发判定对应的测量时刻（UTC）
 * @param archivedAt       归档时间（UTC），NULL 表示未归档
 */
public record ControlEvent(long id, long lineId, ControlEventType type, ControlEventStatus status,
                           Long triggerSegmentId, Double avgSpeed, Instant triggeredAt, Instant archivedAt) {
}
