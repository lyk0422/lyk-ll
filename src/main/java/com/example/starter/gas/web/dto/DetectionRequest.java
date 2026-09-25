package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运行监测（浓度+流量检测）请求。
 *
 * @param actualConsumeG 装置上报的本时段实际加臭剂消耗，单位 g；可空，缺省等于理论消耗
 */
public record DetectionRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime detectedAt,
        @NotNull @Positive BigDecimal concentrationMg,
        @NotNull @PositiveOrZero BigDecimal flowM3,
        @PositiveOrZero BigDecimal actualConsumeG
) {
}
