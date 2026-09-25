package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 压力调节联动请求（同一事务内附带加注调整）。
 *
 * @param flowM3          联动时段流量，单位 m³
 * @param actualConsumeG  装置实际上报消耗，单位 g；可空，缺省等于理论消耗
 */
public record LinkageRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime detectedAt,
        @NotNull BigDecimal pressureKpa,
        @NotNull @PositiveOrZero BigDecimal flowM3,
        @PositiveOrZero BigDecimal actualConsumeG
) {
}
