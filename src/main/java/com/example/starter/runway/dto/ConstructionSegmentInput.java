package com.example.starter.runway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 施工段输入：半开整数米区间 [startMeters, endMeters)。
 *
 * @param segmentNo   段号（1至10，单内唯一）
 * @param startMeters 段起点（单位：米，含），0<=start<end<=跑道长度
 * @param endMeters   段终点（单位：米，不含）
 */
public record ConstructionSegmentInput(
        @Min(1) @Max(10) int segmentNo,
        @PositiveOrZero int startMeters,
        @Min(1) int endMeters) {
}
