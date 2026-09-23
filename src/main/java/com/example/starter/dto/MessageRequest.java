package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设备写入消息请求。
 *
 * @param seq 设备本地单调序号（同一设备+Topic 内递增）
 * @param priority 业务优先级，数值越大越先补发
 */
public record MessageRequest(
        @NotBlank String deviceId,
        @NotBlank String topic,
        @NotNull @PositiveOrZero Long seq,
        @NotNull Integer priority,
        @NotBlank String dedupKey,
        String payload) {
}
