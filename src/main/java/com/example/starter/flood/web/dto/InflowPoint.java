package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * 入库洪水过程线点：相对上一演算版本时刻的分钟偏移与入库流量。
 *
 * @param minute 分钟偏移（首点必须为 0，严格递增）
 * @param inflowRate 入库流量，立方米每秒
 */
public record InflowPoint(
        @NotNull @PositiveOrZero Integer minute,
        @NotNull @DecimalMin("0.0") BigDecimal inflowRate) {
}
