package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 排放读数上报项。按 方案+测点+采样时刻 去重，乱序数据按采样时刻归位。
 * 各污染物浓度单位 mg/Nm3，未提供的污染物传 null。
 */
public record ReadingItem(
        @NotBlank String measurePoint,
        @NotNull LocalDateTime sampleTime,
        BigDecimal nox,
        BigDecimal so2,
        BigDecimal dust) {
}
