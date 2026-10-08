package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 检修分段清空证据登记响应。
 *
 * @param orderId             检修单 id
 * @param segmentId           分段 id
 * @param clearedSegmentCount 已留证清空的分段数
 * @param totalSegments       检修清单分段总数
 * @param allCleared          清单分段是否已全部清空
 * @param operatorId          清空确认人标识
 * @param opinion             清空情况说明
 * @param clearedAt           证据登记时间（UTC）
 */
public record MaintenanceClearanceResponse(long orderId, long segmentId, int clearedSegmentCount,
                                           int totalSegments, boolean allCleared, String operatorId,
                                           String opinion, Instant clearedAt) {
}
