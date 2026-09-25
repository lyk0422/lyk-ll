package com.example.starter.runway;

import java.time.Instant;

/**
 * 气象读数行：对应 weather_reading 表（合成数据）。
 *
 * @param id         读数主键
 * @param runwayId   跑道编号
 * @param rvrMeters  跑道视程RVR（单位：米）
 * @param recordedAt 读数时间（UTC）
 */
public record WeatherReading(
        long id,
        String runwayId,
        int rvrMeters,
        Instant recordedAt) {
}
