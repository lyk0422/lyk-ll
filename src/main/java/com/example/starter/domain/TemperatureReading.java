package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 温度读数。
 *
 * @param shipmentId    所属货件
 * @param sequenceNo    设备序号（货件内全局严格递增，允许乱序到达）
 * @param sampledAt     采样时间，Unix 毫秒时间戳（UTC）
 * @param temperatureC  采样温度（摄氏度，有限数值）
 * @param createdAt     服务端写入时间，Unix 毫秒时间戳（UTC）
 */
public record TemperatureReading(
        String shipmentId,
        long sequenceNo,
        long sampledAt,
        BigDecimal temperatureC,
        long createdAt
) {
}
