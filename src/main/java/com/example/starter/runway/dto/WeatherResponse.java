package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 气象读数响应（合成数据）。
 *
 * @param id         读数ID
 * @param runwayId   跑道编号
 * @param rvrMeters  跑道视程RVR（单位：米）
 * @param recordedAt 读数时间（UTC）
 */
public record WeatherResponse(
        long id,
        String runwayId,
        int rvrMeters,
        Instant recordedAt) {
}
