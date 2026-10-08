package com.example.starter.water;

import java.time.Instant;

/**
 * 展示池行记录；时间字段均为 Asia/Shanghai 墙钟时间对应的 Instant。
 */
public record TankRow(
        long id,
        String code,
        String name,
        TankStatus status,
        boolean displaySuspended,
        boolean autoDisposalSuspended,
        boolean dataMissing,
        int consecutiveViolations,
        SensorStatus sensorStatus,
        Instant sensorLastSeenAt) {
}
