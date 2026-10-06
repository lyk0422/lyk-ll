package com.example.starter.weight.repo.po;

import java.time.Instant;

/**
 * 非现场称重设备状态记录行。时间均为 UTC。
 *
 * @param deviceCode            设备编码
 * @param laneCode              车道编码
 * @param stationCode           所属检测站编码
 * @param status                设备状态（NORMAL/FAULT）
 * @param calibrationValidFrom  校准有效期起（UTC，含）
 * @param calibrationValidUntil 校准有效期止（UTC，含）；有效期外数据不得进入证据链
 */
public record WeighDeviceRow(
        String deviceCode,
        String laneCode,
        String stationCode,
        String status,
        Instant calibrationValidFrom,
        Instant calibrationValidUntil) {
}
