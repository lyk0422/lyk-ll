package com.example.starter.spectrum.service;

/**
 * 半开区间 {@code [low, high)} 数学工具。两个半开区间仅在交集长度严格为正时相遇，
 * 端点相接（一个上界等于另一个下界）不算相遇。
 */
public final class Intervals {

    private Intervals() {
    }

    /**
     * 整型（epoch 毫秒）半开区间是否存在正长度交集。
     */
    public static boolean overlaps(long aLow, long aHigh, long bLow, long bHigh) {
        return Math.max(aLow, bLow) < Math.min(aHigh, bHigh);
    }

    /**
     * 浮点（MHz）半开区间是否存在正长度交集。
     */
    public static boolean overlaps(double aLow, double aHigh, double bLow, double bHigh) {
        return Math.max(aLow, bLow) < Math.min(aHigh, bHigh);
    }

    /**
     * 点 {@code point} 是否落在半开区间 {@code [low, high)} 内。
     */
    public static boolean contains(long low, long high, long point) {
        return low <= point && point < high;
    }

    /**
     * 点 {@code point} 是否落在半开区间 {@code [low, high)} 内。
     */
    public static boolean contains(double low, double high, double point) {
        return low <= point && point < high;
    }
}
