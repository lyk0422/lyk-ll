package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * 排放读数上报项；按“测点＋采样时刻”去重，乱序上报按采样时刻归位。
 *
 * @param pointCode 测点编码
 * @param pollutant  污染物（NOX/SO2/PARTICULATE）
 * @param sampledAt  采样时刻
 * @param valueMgM3  监测值，单位 mg/m3
 */
public record ReadingItem(
        @NotBlank String pointCode,
        @NotBlank String pollutant,
        @NotNull LocalDateTime sampledAt,
        @NotNull BigDecimal valueMgM3) {
}
