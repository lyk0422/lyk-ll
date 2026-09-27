package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 合成设备视图。
 *
 * @param deviceId        设备ID
 * @param deviceName      设备名称
 * @param capabilityLevel 当前可执行最高复杂度等级（1-3）；下调后已执行实绩不随之改变
 * @param version         设备版本号，登记为1，每次成功下调加1
 * @param createdAt       登记时间
 * @param updatedAt      最近能力变更时间
 */
public record DeviceResponse(
        String deviceId,
        String deviceName,
        Integer capabilityLevel,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
