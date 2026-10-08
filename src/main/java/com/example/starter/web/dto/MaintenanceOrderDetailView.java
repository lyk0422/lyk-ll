package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 检修单详情视图：分别展示检修阻断原因、分段清空证据与两岗验收人员意见。
 *
 * @param id                 检修单 id
 * @param lineId             绑定线路 id
 * @param version            当前版本
 * @param status             检修单状态
 * @param effective          当前时刻是否已生效封锁（已到 start 且未终态）
 * @param startAt            窗口起点（UTC，含）
 * @param endAt              窗口终点（UTC，不含）
 * @param reason             检修原因
 * @param completedAt        验收完成时间（UTC），未完成为 null
 * @param cancelledAt        取消时间（UTC），未取消为 null
 * @param segmentIds         涉及分段清单
 * @param clearedSegments    已留证清空的分段数
 * @param totalSegments      清单分段总数
 * @param clearances         分段清空证据
 * @param acceptances        两岗验收人员意见
 */
public record MaintenanceOrderDetailView(long id, long lineId, int version, String status, boolean effective,
                                         Instant startAt, Instant endAt, String reason,
                                         Instant completedAt, Instant cancelledAt,
                                         List<Long> segmentIds, int clearedSegments, int totalSegments,
                                         List<ClearanceView> clearances,
                                         List<AcceptanceView> acceptances) {

    /**
     * 分段清空证据。
     *
     * @param segmentId  分段 id
     * @param operatorId 清空确认人标识
     * @param opinion    清空情况说明
     * @param clearedAt  证据登记时间（UTC）
     */
    public record ClearanceView(long segmentId, String operatorId, String opinion, Instant clearedAt) {
    }

    /**
     * 验收人员意见。
     *
     * @param role       岗位：MECHANICAL 机务 / REPAIR 检修
     * @param operatorId 验收人员标识
     * @param opinion    验收人员意见
     * @param acceptedAt 验收时间（UTC）
     */
    public record AcceptanceView(String role, String operatorId, String opinion, Instant acceptedAt) {
    }
}
