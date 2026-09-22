package com.example.starter.sync.domain;

import com.example.starter.sync.SensorType;

/**
 * 融合帧的单个输入证据。interpolated 为 true 时 messageId 为前后两条样本消息 ID 以 + 连接，
 * correctedTs 等于目标融合时刻，deltaMs 为 0。
 */
public record FrameInput(
        SensorType sensorType,
        String messageId,
        long rawTs,
        long correctedTs,
        long deltaMs,
        boolean interpolated) {
}
