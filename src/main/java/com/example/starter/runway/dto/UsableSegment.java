package com.example.starter.runway.dto;

/**
 * 可用区间：半开整数米区间 [startMeter, endMeter)。
 *
 * @param startMeter 区间起点（单位：米，含）
 * @param endMeter   区间终点（单位：米，不含）
 */
public record UsableSegment(
        int startMeter,
        int endMeter) {
}
