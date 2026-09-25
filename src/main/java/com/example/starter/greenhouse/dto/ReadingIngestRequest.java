package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 传感器读数上报请求。sampleTime 为采样时刻（UTC，ISO-8601，如 2026-09-25T01:00:00Z），
 * 按“分区 + 采样时刻”去重，乱序上报按此时刻归位。
 */
public record ReadingIngestRequest(
        @NotBlank(message = "不能为空") String zoneCode,
        @NotBlank(message = "不能为空") String sampleTime,
        @NotNull @DecimalMin("-50.00") @DecimalMax("60.00") BigDecimal temperature,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal humidity) {
}
