package com.example.starter.runway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 气象读数上报请求（合成数据）。
 *
 * @param runwayId   跑道编号
 * @param rvrMeters  跑道视程RVR（单位：米）
 * @param recordedAt 读数时间（UTC）
 */
public record WeatherRequest(
        @NotBlank String runwayId,
        @Min(0) int rvrMeters,
        @NotNull Instant recordedAt) {
}
