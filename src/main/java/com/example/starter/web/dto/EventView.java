package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 管制事件视图。
 *
 * @param id               事件 id
 * @param lineId           线路 id
 * @param type             事件类型
 * @param status           事件状态
 * @param triggerSegmentId 触发分段 id
 * @param avgSpeed         触发时 10 分钟平均风速（m/s）
 * @param triggeredAt      触发时刻（UTC）
 * @param archivedAt       归档时间（UTC），未归档为 null
 * @param clearedSegments  已双人确认清空的分段数
 * @param totalSegments    线路分段总数
 * @param reopenRoles      已确认的复开岗位
 */
public record EventView(long id, long lineId, String type, String status, Long triggerSegmentId,
                        Double avgSpeed, Instant triggeredAt, Instant archivedAt,
                        int clearedSegments, int totalSegments, List<String> reopenRoles) {
}
