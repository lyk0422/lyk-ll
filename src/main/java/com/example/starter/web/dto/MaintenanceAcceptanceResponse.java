package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 检修验收响应：两个不同人员分别完成机务、检修验收且不早于 end 后方可结束封锁。
 *
 * @param id                  检修单 id
 * @param version             当前版本（验收完成加 1）
 * @param status              检修单状态
 * @param mechanicalConfirmed 机务岗是否已验收
 * @param repairConfirmed     检修岗是否已验收
 * @param completed           是否已双岗验收完成（仅解除检修自身阻断）
 * @param completedAt         完成时间（UTC），未完成为 null
 * @param acceptances         已有验收人员意见
 */
public record MaintenanceAcceptanceResponse(long id, int version, String status,
                                            boolean mechanicalConfirmed, boolean repairConfirmed,
                                            boolean completed, Instant completedAt,
                                            List<AcceptanceView> acceptances) {
    /**
     * 单条验收意见。
     *
     * @param role       岗位
     * @param operatorId 验收人员标识
     * @param opinion    验收人员意见
     * @param acceptedAt 验收时间（UTC）
     */
    public record AcceptanceView(String role, String operatorId, String opinion, Instant acceptedAt) {
    }
}
