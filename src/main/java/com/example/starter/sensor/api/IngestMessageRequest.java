package com.example.starter.sensor.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 传感器消息摄取请求。
 *
 * @param messageId    消息唯一 ID（同一传感器内唯一），用于重复检测与证据定位
 * @param rawTimestamp 传感器原始时钟时戳（epoch 纳秒，未校正）
 * @param payload      合成数据负载（JSON 字符串，允许为空）
 */
public record IngestMessageRequest(
        @NotBlank String messageId,
        @NotNull Long rawTimestamp,
        String payload) {
}
