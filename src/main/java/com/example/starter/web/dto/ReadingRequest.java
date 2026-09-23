package com.example.starter.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 写入温度读数请求。
 *
 * @param sequenceNo    设备序号（全局严格递增，允许乱序到达）
 * @param sampledAt     采样时间，Unix 毫秒时间戳（UTC）
 * @param temperatureC  采样温度（摄氏度，有限数值）
 */
public record ReadingRequest(
        @NotNull Long sequenceNo,
        @NotNull Long sampledAt,
        @NotNull BigDecimal temperatureC
) {
}
