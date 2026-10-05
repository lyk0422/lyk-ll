package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入库洪水过程线点。
 *
 * @param forecastTime 预报时刻（时区 Asia/Shanghai）
 * @param inflowRate 该时刻入库流量，立方米每秒
 */
public record HydrographPointItem(
        @NotNull LocalDateTime forecastTime,
        @NotNull @DecimalMin("0.0") BigDecimal inflowRate) {
}
