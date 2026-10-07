package com.example.starter.model;

import java.time.Instant;

/**
 * 检修验收记录行。
 *
 * @param role       验收岗位
 * @param operatorId 验收人标识
 * @param opinion    验收意见，可为 null
 * @param acceptedAt 验收时间（UTC）
 */
public record MaintenanceAcceptance(AcceptanceRole role, String operatorId, String opinion, Instant acceptedAt) {
}
