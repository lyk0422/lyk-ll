package com.example.starter.coldchain.dto;

import com.example.starter.coldchain.entity.TemperatureReading;

import java.time.LocalDateTime;

/**
 * 读数写入结果。
 *
 * @param sequenceNo   序号
 * @param deviceNo     设备序号
 * @param sampledAt    采样时间
 * @param temperature  采样温度
 * @param replayed     是否为同序号同参重放
 */
public record ReadingResponse(
        long sequenceNo,
        String deviceNo,
        LocalDateTime sampledAt,
        double temperature,
        boolean replayed
) {

    public static ReadingResponse of(TemperatureReading reading, boolean replayed) {
        return new ReadingResponse(
                reading.sequenceNo(),
                reading.deviceNo(),
                reading.sampledAt(),
                reading.temperature(),
                replayed
        );
    }
}
