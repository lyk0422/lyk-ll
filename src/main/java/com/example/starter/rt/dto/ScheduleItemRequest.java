package com.example.starter.rt.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 排程发布明细项：单个连续分次的 UTC 占用窗口（半开区间 [startAt, endAt)）。
 *
 * @param fractionNo 分次号（批内连续）
 * @param startAt    窗口开始（UTC，含起点）
 * @param endAt      窗口结束（UTC，不含终点），长度须等于等级对应分钟数
 */
public record ScheduleItemRequest(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer fractionNo,
        @NotNull(message = "不能为空") Instant startAt,
        @NotNull(message = "不能为空") Instant endAt) {
}
