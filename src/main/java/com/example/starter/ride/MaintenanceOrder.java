package com.example.starter.ride;

import java.time.Instant;

/**
 * 检修单实体（合成数据，仅模拟挂牌流程，不控制真实装置）。
 *
 * @param id              检修单主键
 * @param rideId          绑定设备ID，关联 ride.id
 * @param requestId       建单幂等请求ID
 * @param status          检修单状态，见 {@link MaintenanceStatus}
 * @param priorStatus     启动前设备状态：结束时原故障停运仍为 STOPPED，其余转为 SUSPENDED，NULL 表示尚未启动
 * @param version         单据版本号，从1开始；启动、结束等修改成功后加1，修改须携带 expectedVersion
 * @param startedAt       启动时刻（UTC），NULL 表示尚未启动
 * @param startRequestId  启动幂等请求ID，NULL 表示尚未启动
 * @param finishedAt      结束时刻（UTC），NULL 表示尚未结束
 * @param finishRequestId 结束幂等请求ID，NULL 表示尚未结束
 */
public record MaintenanceOrder(
        long id,
        long rideId,
        String requestId,
        MaintenanceStatus status,
        RideStatus priorStatus,
        long version,
        Instant startedAt,
        String startRequestId,
        Instant finishedAt,
        String finishRequestId) {
}
