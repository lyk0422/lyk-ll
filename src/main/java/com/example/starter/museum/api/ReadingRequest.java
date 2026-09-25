package com.example.starter.museum.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温湿度读数上报。按 (showcaseNo, sampleTime) 去重，乱序按 sampleTime 归位判异。
 * temperature 单位摄氏度；humidity 单位 %RH；sampleTime 为 UTC 采样时刻。
 */
public record ReadingRequest(
        @NotBlank String requestId,
        @NotBlank String showcaseNo,
        @NotNull Instant sampleTime,
        @NotNull BigDecimal temperature,
        @NotNull BigDecimal humidity
) {
}
