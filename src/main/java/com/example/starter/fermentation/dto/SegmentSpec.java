package com.example.starter.fermentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 工艺曲线分段定义：相对满罐时刻 [fromHour, toHour) 小时区间内的目标温度（℃）。
 */
public record SegmentSpec(
        @NotNull @Min(0) Integer fromHour,
        @NotNull @Min(0) Integer toHour,
        @NotNull @DecimalMin("-273.15") BigDecimal targetTemp) {
}
