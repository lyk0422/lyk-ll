package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 调度单内单车到场视图。
 *
 * @param vehicleId   发电车ID
 * @param code        发电车编号
 * @param carriedOver 是否改派时从旧单保留
 * @param arrivedAt   到场时刻（UTC），空表示未到场
 */
public record OrderVehicleView(
        Long vehicleId,
        String code,
        boolean carriedOver,
        LocalDateTime arrivedAt) {
}
