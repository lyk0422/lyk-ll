package com.example.starter.ride;

import java.time.Instant;

/**
 * 检修挂牌单实体（合成数据）。
 *
 * @param id         检修单主键
 * @param rideId     设备ID
 * @param status     检修单状态，见 {@link MaintenanceStatus}
 * @param version    单据版本号，从1开始，每次成功修改加1，修改须携带 expectedVersion
 * @param startedAt  启动检修时刻（UTC），NULL 表示未启动
 * @param finishedAt 结束检修时刻（UTC），NULL 表示未结束
 */
public record MaintenanceOrder(
        long id,
        long rideId,
        MaintenanceStatus status,
        long version,
        Instant startedAt,
        Instant finishedAt) {

    /** 挂牌人员上限：1至20名不同人员。 */
    public static final int MAX_PERSONNEL = 20;
}
