package com.example.starter.model;

import java.time.Instant;

/**
 * 检修验收意见行记录。
 *
 * @param orderId    检修单 id
 * @param role       验收岗位：MECHANICAL 机务 / REPAIR 检修
 * @param operatorId 验收人员标识
 * @param opinion    验收人员意见
 * @param createdAt  验收时间（UTC）
 */
public record MaintenanceAcceptance(long orderId, MaintenanceAcceptanceRole role, String operatorId,
                                    String opinion, Instant createdAt) {
}
