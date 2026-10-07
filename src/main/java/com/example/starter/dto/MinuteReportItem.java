package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 受控分段回执中的单机组分钟功率。
 *
 * @param turbineCode 机组编码
 * @param powerKw     该分钟实际功率（kW），须等于冻结的该分钟阶梯功率
 */
public record MinuteReportItem(
        @NotBlank String turbineCode,
        @PositiveOrZero int powerKw) {
}
