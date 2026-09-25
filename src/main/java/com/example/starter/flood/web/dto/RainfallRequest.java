package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 降雨读数上报请求；同一（测点, 采样时刻）重复上报去重，乱序按采样时刻归位。
 *
 * @param stationCode 雨量测点编码
 * @param sampleTime 采样时刻（Asia/Shanghai）
 * @param rainfallDepth 时段降雨量，毫米
 * @param durationMinutes 降雨历时，分钟
 */
public record RainfallRequest(
        @NotBlank String stationCode,
        @NotNull LocalDateTime sampleTime,
        @NotNull @DecimalMin("0.0") BigDecimal rainfallDepth,
        @Positive int durationMinutes) {
}
