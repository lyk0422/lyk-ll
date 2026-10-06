package com.example.starter.weight.repo.po;

import java.time.Instant;

/**
 * 证据链缺项人工待补记录行。时间均为 UTC。
 *
 * @param id             主键
 * @param offsiteWeighId 非现场称重记录主键
 * @param plateNo        车牌号
 * @param laneNo         车道号
 * @param weighTime      过磅时刻（UTC）
 * @param reason         待补原因（MISSING_IMAGE/DEVICE_STATUS_MISSING/DEVICE_CAL_EXPIRED）
 * @param status         处理状态（PENDING 待补）
 * @param createdAt      转人工时间（UTC）
 */
public record ManualPendingRow(
        Long id,
        long offsiteWeighId,
        String plateNo,
        String laneNo,
        Instant weighTime,
        String reason,
        String status,
        Instant createdAt) {
}
