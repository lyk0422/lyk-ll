package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 排程发布请求中的单分次窗口项。窗口为 UTC 半开区间 [start, end)，
 * 长度须等于批次等级对应分钟数（10/20/30）。
 *
 * @param fractionNo    分次号
 * @param windowStartUtc 窗口开始（UTC，含）
 * @param windowEndUtc   窗口结束（UTC，不含）
 */
public record ScheduleItemRequest(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer fractionNo,
        @NotNull(message = "不能为空") OffsetDateTime windowStartUtc,
        @NotNull(message = "不能为空") OffsetDateTime windowEndUtc) {
}
