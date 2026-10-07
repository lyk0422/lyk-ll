package com.example.starter.runway.dto;

/**
 * 米制半开区间视图：[startMeters, endMeters)。
 *
 * @param startMeters 区间起点（单位：米，含）
 * @param endMeters   区间终点（单位：米，不含）
 * @param lengthMeters 区间长度（单位：米，endMeters-startMeters）
 */
public record IntervalView(
        int startMeters,
        int endMeters,
        int lengthMeters) {
}
