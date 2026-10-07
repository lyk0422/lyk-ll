package com.example.starter.model;

import java.time.Instant;

/**
 * 检修封锁窗口行记录。
 *
 * @param id          检修单 id
 * @param lineId      所属线路 id
 * @param status      工单状态
 * @param version     乐观版本号，从 1 起
 * @param startAt     封锁窗口起点（UTC，含）
 * @param endAt       封锁窗口终点（UTC，不含）
 * @param cancelledAt 取消时间（UTC），未取消为 null
 * @param completedAt 完成时间（UTC），未完成为 null
 */
public record MaintenanceOrder(long id, long lineId, MaintenanceOrderStatus status, int version,
                               Instant startAt, Instant endAt,
                               Instant cancelledAt, Instant completedAt) {

    /**
     * 当前时刻是否构成阻断：已登记且已到达窗口起点（到 end 未验收仍阻断）。
     */
    public boolean blocksAt(Instant now) {
        return status == MaintenanceOrderStatus.SCHEDULED && !now.isBefore(startAt);
    }
}
