package com.example.starter.sensor.api;

import com.example.starter.sensor.domain.IngestStatus;

/**
 * 传感器消息摄取响应。
 *
 * @param status             ACCEPTED / LATE / DUPLICATE
 * @param sensor             传感器类型
 * @param messageId          消息 ID
 * @param rawTimestamp       原始时戳（epoch 纳秒）
 * @param correctedTimestamp 漂移校正后时戳（epoch 纳秒）
 * @param watermark          摄取后该传感器的事件时间水位线（epoch 纳秒）
 * @param reason             非 ACCEPTED 时的稳定丢弃原因，ACCEPTED 时为 null
 */
public record IngestMessageResponse(
        IngestStatus status,
        String sensor,
        String messageId,
        Long rawTimestamp,
        Long correctedTimestamp,
        Long watermark,
        String reason) {
}
