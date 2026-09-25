package com.example.starter.blast.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 振动监测读数回传请求体。
 * 去重自然键为：爆区＋测点＋采样时刻。
 *
 * @param applicationId 读数归属的已备案作业 ID
 * @param zoneCode      爆区编码
 * @param pointCode     测点编码
 * @param sampleTime    采样时刻，UTC
 * @param velocityMmS   质点振动速度峰值，单位 mm/s
 */
public record VibrationReadingRequest(
        @NotNull Long applicationId,
        @NotBlank String zoneCode,
        @NotBlank String pointCode,
        @NotNull Instant sampleTime,
        @NotNull @PositiveOrZero BigDecimal velocityMmS) {
}
