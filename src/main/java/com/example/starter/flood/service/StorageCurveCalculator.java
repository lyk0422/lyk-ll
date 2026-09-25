package com.example.starter.flood.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 库容曲线计算器：按水位-蓄水量散点做分段线性插值/反查。
 * 水位超出曲线范围时按端点外延会带来失真，因此这里要求水位必须落在曲线区间内。
 */
@Component
public class StorageCurveCalculator {

    /** 曲线点（不可变值对象）。 */
    public record CurvePoint(BigDecimal waterLevel, BigDecimal storage) {
    }

    /**
     * 由当前库水位反查当前蓄水量：恰等于曲线上的点时取该点，否则线性插值。
     *
     * @param points 该库全部曲线点（无需预排序）
     * @param waterLevel 当前库水位，米
     * @return 当前蓄水量，立方米（保留 4 位小数，四舍五入）
     */
    public BigDecimal storageAt(List<CurvePoint> points, BigDecimal waterLevel) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("库容曲线为空，无法反查蓄水量");
        }
        List<CurvePoint> sorted = points.stream()
                .sorted(Comparator.comparing(CurvePoint::waterLevel))
                .toList();
        CurvePoint first = sorted.get(0);
        CurvePoint last = sorted.get(sorted.size() - 1);
        if (waterLevel.compareTo(first.waterLevel()) < 0
                || waterLevel.compareTo(last.waterLevel()) > 0) {
            throw new IllegalArgumentException(
                    "当前库水位 " + waterLevel + " 超出合成库容曲线范围 ["
                            + first.waterLevel() + ", " + last.waterLevel() + "]");
        }
        for (int i = 0; i < sorted.size() - 1; i++) {
            CurvePoint low = sorted.get(i);
            CurvePoint high = sorted.get(i + 1);
            int cmpLow = waterLevel.compareTo(low.waterLevel());
            int cmpHigh = waterLevel.compareTo(high.waterLevel());
            if (cmpLow == 0) {
                return low.storage();
            }
            if (cmpHigh == 0) {
                return high.storage();
            }
            if (cmpLow > 0 && cmpHigh < 0) {
                BigDecimal ratio = waterLevel.subtract(low.waterLevel())
                        .divide(high.waterLevel().subtract(low.waterLevel()), 12, RoundingMode.HALF_UP);
                return low.storage()
                        .add(high.storage().subtract(low.storage()).multiply(ratio))
                        .setScale(4, RoundingMode.HALF_UP);
            }
        }
        return last.storage();
    }
}
