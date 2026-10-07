package com.example.starter.runway.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 施工段输入：半开整数米区间 [startMeter, endMeter)。
 *
 * @param segmentNo  段号：单内唯一，1至10个不同段号
 * @param startMeter 区间起点（单位：米，含），0<=start<end<=跑道长度
 * @param endMeter   区间终点（单位：米，不含）
 */
public record SegmentInput(
        @Positive int segmentNo,
        @PositiveOrZero int startMeter,
        @Positive int endMeter) {
}
