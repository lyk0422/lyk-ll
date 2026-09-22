package com.example.starter.sensor.po;

/**
 * 传感器原始消息事件行（重放与重建的事实来源）。
 *
 * @param sensor     传感器类型
 * @param messageId  消息 ID
 * @param rawTs      原始时戳，epoch 纳秒
 * @param payload    合成负载，可为 null
 * @param receivedAt 到达墙钟时刻，epoch 毫秒
 */
public record MessageRow(
        String sensor,
        String messageId,
        long rawTs,
        String payload,
        long receivedAt) {
}
