package com.example.starter.waterlogging.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 液位复测数据上报，按“站点＋时刻”去重。
 *
 * @param requestId    幂等键
 * @param stationCode  上报站点
 * @param readingTime  数据时刻
 * @param level        实测水位（米）
 */
public record LevelReadingRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull LocalDateTime readingTime,
        @NotNull @DecimalMin("0.00") BigDecimal level) {
}
