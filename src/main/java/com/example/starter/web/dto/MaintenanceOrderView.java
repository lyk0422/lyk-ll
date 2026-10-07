package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 检修单视图：含窗口、版本、分段清空证据与验收人员意见。
 *
 * @param id          检修单 id
 * @param lineId      所属线路 id
 * @param status      工单状态：SCHEDULED / CANCELLED / COMPLETED
 * @param version     当前版本号
 * @param startAt     封锁窗口起点（UTC，含）
 * @param endAt       封锁窗口终点（UTC，不含）
 * @param blocked     查询时刻是否处于阻断中
 * @param segments    涉及分段及清空证据
 * @param acceptances 验收记录（岗位、人员、意见）
 * @param cancelledAt 取消时间（UTC），未取消为 null
 * @param completedAt 完成时间（UTC），未完成为 null
 */
public record MaintenanceOrderView(long id, long lineId, String status, int version,
                                   Instant startAt, Instant endAt, boolean blocked,
                                   List<SegmentClearanceView> segments,
                                   List<AcceptanceView> acceptances,
                                   Instant cancelledAt, Instant completedAt) {

    /**
     * 分段清空证据视图。
     *
     * @param segmentId  分段 id
     * @param cleared    是否已登记清空
     * @param clearedBy  清空操作人，未清空为 null
     * @param clearedAt  清空登记时间（UTC），未清空为 null
     */
    public record SegmentClearanceView(long segmentId, boolean cleared, String clearedBy, Instant clearedAt) {
    }

    /**
     * 验收记录视图。
     *
     * @param role       验收岗位
     * @param operatorId 验收人标识
     * @param opinion    验收意见，可为 null
     * @param acceptedAt 验收时间（UTC）
     */
    public record AcceptanceView(String role, String operatorId, String opinion, Instant acceptedAt) {
    }
}
