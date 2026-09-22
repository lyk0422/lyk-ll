package com.example.starter.sensor.api;

/**
 * 丢弃消息记录视图（迟到 / 重复 / 乱序且晚于水位线的消息）。
 *
 * @param id            记录 ID
 * @param sensor        传感器类型
 * @param messageId     消息 ID
 * @param rawTimestamp  原始时戳（epoch 纳秒）
 * @param receivedAt    到达时刻（墙钟 epoch 毫秒）
 * @param reason        稳定原因码：LATE / DUPLICATE
 * @param reasonDetail  可定位的原因描述（含水位线值等）
 */
public record DroppedMessageView(
        long id,
        String sensor,
        String messageId,
        Long rawTimestamp,
        Long receivedAt,
        String reason,
        String reasonDetail) {
}
