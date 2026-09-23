package com.example.starter.coldchain.entity;

import java.time.LocalDateTime;

/**
 * 温度读数持久化对象。
 *
 * @param id           自增主键
 * @param shipmentId   货件标识
 * @param sequenceNo   设备采样序号，货件内全局严格递增且唯一
 * @param deviceNo     设备序号（设备标识）
 * @param sampledAt    采样时间（Asia/Shanghai）
 * @param temperature  采样温度（摄氏度，有限数值）
 */
public record TemperatureReading(
        Long id,
        String shipmentId,
        long sequenceNo,
        String deviceNo,
        LocalDateTime sampledAt,
        double temperature
) {
}
