package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 计量读数上报请求。按“交接站＋表底时刻”去重，允许乱序到达后归位。
 *
 * @param stationCode 交接站编码
 * @param meterId 流量计编号
 * @param readingTime 表底时刻（UTC）
 * @param meterIndex 该时刻累计表底（标况体积），单位 Nm3
 * @param heatingValue 该采样点热值，单位 MJ/Nm3
 */
public record ReadingRequest(
        @NotBlank String stationCode,
        @NotBlank String meterId,
        @NotNull Instant readingTime,
        @NotNull @DecimalMin("0.0") BigDecimal meterIndex,
        @NotNull @DecimalMin("0.0") BigDecimal heatingValue) {
}
