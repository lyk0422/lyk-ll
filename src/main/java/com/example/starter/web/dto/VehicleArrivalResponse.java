package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 发电车到场确认结果。全部车确认且未超时才供电，部分到场不算全负荷恢复。
 *
 * @param orderId         调度单ID
 * @param vehicleId       本次到场发电车ID
 * @param version         当前版本
 * @param status          调度单状态
 * @param arrivedCount    已到场车数
 * @param totalVehicles   本单车总数
 * @param supplied        是否全部到场并开始临时供电
 * @param suppliedAt      临时供电开始时刻（UTC），空表示未供电
 * @param late            是否为旧单终止（已改派/已撤销）或超时后的迟到到场：仅记事件不占车
 * @param replayed        是否为同键同参重放
 */
public record VehicleArrivalResponse(
        Long orderId,
        Long vehicleId,
        int version,
        String status,
        int arrivedCount,
        int totalVehicles,
        boolean supplied,
        LocalDateTime suppliedAt,
        boolean late,
        boolean replayed) {
}
