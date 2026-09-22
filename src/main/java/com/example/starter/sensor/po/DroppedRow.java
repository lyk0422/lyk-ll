package com.example.starter.sensor.po;

/**
 * 丢弃消息行（迟到/重复）。
 *
 * @param id           记录 ID
 * @param sensor       传感器类型
 * @param messageId    消息 ID
 * @param rawTs        原始时戳，epoch 纳秒，可为 null
 * @param receivedAt   到达墙钟时刻，epoch 毫秒
 * @param reason       原因码 LATE / DUPLICATE
 * @param reasonDetail 原因描述
 */
public record DroppedRow(
        long id,
        String sensor,
        String messageId,
        Long rawTs,
        long receivedAt,
        String reason,
        String reasonDetail) {
}
