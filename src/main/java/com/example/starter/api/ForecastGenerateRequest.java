package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 节假日峰值预测并自动生成临时加钞计划请求。
 * holidayDate 须存在于合成节假日日历，且当前 UTC 日期须落在节前 3 日窗口内。
 */
public record ForecastGenerateRequest(
        @NotBlank String requestId,
        @NotNull LocalDate holidayDate
) {
}
