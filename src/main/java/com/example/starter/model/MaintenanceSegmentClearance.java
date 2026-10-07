package com.example.starter.model;

import java.time.Instant;

/**
 * 检修分段清空证据行记录。
 *
 * @param segmentId  已清空分段 id
 * @param operatorId 清空操作人标识
 * @param note       清空说明，可为 null
 * @param clearedAt  清空登记时间（UTC）
 */
public record MaintenanceSegmentClearance(long segmentId, String operatorId, String note, Instant clearedAt) {
}
