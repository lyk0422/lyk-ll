package com.example.starter.model;

import java.time.Instant;

/**
 * 检修分段清空证据行记录。
 *
 * @param orderId    检修单 id
 * @param segmentId  已清空分段 id
 * @param operatorId 清空确认人标识
 * @param opinion    清空情况说明
 * @param createdAt  证据登记时间（UTC）
 */
public record MaintenanceClearance(long orderId, long segmentId, String operatorId,
                                   String opinion, Instant createdAt) {
}
