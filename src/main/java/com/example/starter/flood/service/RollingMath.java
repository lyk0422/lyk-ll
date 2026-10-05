package com.example.starter.flood.service;

import com.example.starter.flood.domain.ReleaseJudgment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 滚动演算纯计算：入库洪水过程线取值与泄流能力判定，集中实现边界口径，
 * 保证滚动演算与任一版本单独复算完全一致。
 *
 * <p>过程线取值：恰等于过程线点取该点；两点之间线性插值；超出过程线范围按端点值持平。</p>
 * <p>泄流需求：过程线剩余段（预报时刻不早于演算基准时刻）的峰值入库流量；
 * 基准时刻之后无点时取基准时刻流量。</p>
 * <p>泄流能力判定：能力 &gt; 需求判充足；恰等判临界（不触发提级）；小于判不足。</p>
 */
public final class RollingMath {

    /** 滚动演算时段秒数（30 分钟）。 */
    public static final BigDecimal PERIOD_SECONDS = new BigDecimal("1800");

    private RollingMath() {
    }

    /** 过程线点（不可变值对象）。 */
    public record HydroPoint(LocalDateTime forecastTime, BigDecimal inflowRate) {
    }

    /**
     * 取基准时刻入库流量：恰等点取该点，区间内线性插值，界外按端点持平。
     *
     * @param points 过程线点（无需预排序），不能为空
     * @param time 演算基准时刻
     * @return 入库流量，立方米每秒（保留 3 位小数，四舍五入）
     */
    public static BigDecimal rateAt(List<HydroPoint> points, LocalDateTime time) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("入库洪水过程线为空，无法滚动演算");
        }
        List<HydroPoint> sorted = points.stream()
                .sorted(Comparator.comparing(HydroPoint::forecastTime))
                .toList();
        HydroPoint first = sorted.get(0);
        HydroPoint last = sorted.get(sorted.size() - 1);
        if (!time.isAfter(first.forecastTime())) {
            return first.inflowRate();
        }
        if (!time.isBefore(last.forecastTime())) {
            return last.inflowRate();
        }
        for (int i = 0; i < sorted.size() - 1; i++) {
            HydroPoint low = sorted.get(i);
            HydroPoint high = sorted.get(i + 1);
            if (time.equals(low.forecastTime())) {
                return low.inflowRate();
            }
            if (time.isAfter(low.forecastTime()) && time.isBefore(high.forecastTime())) {
                long span = Duration.between(low.forecastTime(), high.forecastTime()).toMillis();
                long offset = Duration.between(low.forecastTime(), time).toMillis();
                BigDecimal ratio = BigDecimal.valueOf(offset)
                        .divide(BigDecimal.valueOf(span), 12, RoundingMode.HALF_UP);
                return low.inflowRate()
                        .add(high.inflowRate().subtract(low.inflowRate()).multiply(ratio))
                        .setScale(3, RoundingMode.HALF_UP);
            }
        }
        return last.inflowRate();
    }

    /**
     * 泄流能力需求：过程线剩余段（预报时刻不早于基准时刻）峰值入库流量；
     * 基准时刻之后无点时取基准时刻流量。
     */
    public static BigDecimal requiredRelease(List<HydroPoint> points, LocalDateTime time) {
        BigDecimal peak = null;
        for (HydroPoint point : points) {
            if (!point.forecastTime().isBefore(time)) {
                if (peak == null || point.inflowRate().compareTo(peak) > 0) {
                    peak = point.inflowRate();
                }
            }
        }
        return peak == null ? rateAt(points, time) : peak;
    }

    /**
     * 泄流能力判定：能力恰等需求判临界，小于判不足。
     *
     * @param releaseCapacity 当前生效方案泄流能力，立方米每秒
     * @param requiredRelease 过程线剩余段峰值需求，立方米每秒
     */
    public static ReleaseJudgment releaseJudgment(BigDecimal releaseCapacity,
                                                  BigDecimal requiredRelease) {
        int cmp = releaseCapacity.compareTo(requiredRelease);
        if (cmp < 0) {
            return ReleaseJudgment.INSUFFICIENT;
        }
        if (cmp == 0) {
            return ReleaseJudgment.CRITICAL;
        }
        return ReleaseJudgment.SUFFICIENT;
    }

    /**
     * 下一演算时刻预计蓄水量 = 当前蓄水 + 时段入库 − 时段泄流，下限 0（泄流不得透支库存）。
     */
    public static BigDecimal projectedStorage(BigDecimal currentStorage, BigDecimal inflowRate,
                                              BigDecimal releaseCapacity) {
        BigDecimal projected = currentStorage
                .add(inflowRate.multiply(PERIOD_SECONDS))
                .subtract(releaseCapacity.multiply(PERIOD_SECONDS));
        if (projected.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return projected.setScale(4, RoundingMode.HALF_UP);
    }
}
