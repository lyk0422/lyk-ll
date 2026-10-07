package com.example.starter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 冷链运输片段输入：半开区间 [start,end)，正长度；相邻片段端点完全相接。
 *
 * @param seq      片段顺序号（从 1 起连续递增）
 * @param start    片段起点 UTC 精确时刻
 * @param end      片段终点 UTC 精确时刻（读数闭区间含该端点）
 * @param readings 本片段温度读数（须含 start 与 end 两端样本）
 */
public record SegmentInput(
        @NotNull @Positive Integer seq,
        @NotNull Instant start,
        @NotNull Instant end,
        @NotNull java.util.List<ReadingInput> readings
) {
}
