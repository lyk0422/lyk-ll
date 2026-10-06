package com.example.starter.weight.repo.po;

import java.time.Instant;

/**
 * 非现场称重设备状态记录行。时间均为 UTC。
 *
 * @param deviceId        设备编号
 * @param laneNo          车道号
 * @param status          设备状态（NORMAL/FAULT）
 * @param calibratedFrom  校准有效期起（UTC，含边界）
 * @param calibratedUntil 校准有效期止（UTC，含边界）
 * @param recordedAt      设备状态记录时间（UTC）
 */
public record DeviceStatusRow(
        String deviceId,
        String laneNo,
        String status,
        Instant calibratedFrom,
        Instant calibratedUntil,
        Instant recordedAt) {
}
