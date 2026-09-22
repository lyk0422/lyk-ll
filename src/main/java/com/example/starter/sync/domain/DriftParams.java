package com.example.starter.sync.domain;

import com.example.starter.sync.SensorType;

/**
 * 时钟漂移校正参数。校正时戳 = 原始时戳 + offsetMs；
 * 仅作用于原始时戳不小于 effectiveFrom 的消息，版本在同一传感器内单调递增。
 */
public record DriftParams(
        SensorType sensorType,
        int version,
        long offsetMs,
        long effectiveFrom) {

    /**
     * 无校正参数时使用的默认值：版本 0、偏移 0、自纪元起生效。
     */
    public static DriftParams none(SensorType type) {
        return new DriftParams(type, 0, 0L, Long.MIN_VALUE);
    }
}
