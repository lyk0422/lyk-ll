package com.example.starter.model;

import java.time.Instant;

/**
 * 检修封锁单行记录。
 *
 * @param id          检修单 id
 * @param lineId      绑定线路 id
 * @param version     乐观锁版本，新建为 1，每次成功修改/取消/验收完成加 1
 * @param status      检修单状态
 * @param startAt     窗口起点（UTC，含）
 * @param endAt       窗口终点（UTC，不含）
 * @param reason      检修原因
 * @param createdAt   登记时间（UTC）
 * @param completedAt 验收完成时间（UTC），未完成为 null
 * @param cancelledAt 取消时间（UTC），未取消为 null
 */
public record MaintenanceOrder(long id, long lineId, int version, MaintenanceOrderStatus status,
                               Instant startAt, Instant endAt, String reason, Instant createdAt,
                               Instant completedAt, Instant cancelledAt) {

    /**
     * 指定时刻是否处于检修窗口内（[start,end)）。
     */
    public boolean covers(Instant now) {
        return !now.isBefore(startAt) && now.isBefore(endAt);
    }

    /**
     * 指定时刻检修是否已生效（已到起点且未终态）。
     */
    public boolean activeAt(Instant now) {
        return status == MaintenanceOrderStatus.SCHEDULED && !now.isBefore(startAt);
    }
}
