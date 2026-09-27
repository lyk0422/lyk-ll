package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 批量排程中的单个分次占用窗口（UTC 半开区间 [windowStartUtc, windowEndUtc)）。
 *
 * @param fractionNo    分次号（批内连续）
 * @param windowStartUtc 占用窗口起点（UTC，含）
 * @param windowEndUtc   占用窗口终点（UTC，不含；长度须等于等级对应分钟数）
 */
public record ScheduleFractionItem(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer fractionNo,
        @NotNull(message = "不能为空") OffsetDateTime windowStartUtc,
        @NotNull(message = "不能为空") OffsetDateTime windowEndUtc) {
}
