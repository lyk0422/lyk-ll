package com.example.starter.sync.domain;

import com.example.starter.sync.SensorType;

/**
 * 已接收的传感器样本。rawTs 为原始时戳，correctedTs 为按 driftVersion 校正后的时戳，单位均为纪元毫秒。
 */
public record SensorSample(
        SensorType sensorType,
        String messageId,
        long rawTs,
        long correctedTs,
        int driftVersion) {
}
