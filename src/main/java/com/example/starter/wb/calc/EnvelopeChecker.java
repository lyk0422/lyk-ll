package com.example.starter.wb.calc;

import java.math.BigDecimal;
import java.util.List;

/**
 * 重心包线判定：以（重量 kg，%MAC）为坐标的凸/凹多边形，
 * 采用射线法判定点是否在内部；恰在边界（顶点或边上）判定为合法。
 */
public final class EnvelopeChecker {

    /** 边界判定容差，吸收四舍五入误差，不改变“恰等合法”的语义。 */
    private static final BigDecimal EPS = new BigDecimal("0.000001");

    private EnvelopeChecker() {
    }

    /**
     * @param polygon 按顺序排列的包线顶点（重量，%MAC）
     * @param weight  待判定重量
     * @param macPct  待判定重心 %MAC
     * @return true 表示在包线内或恰在边界
     */
    public static boolean contains(List<EnvelopeVertex> polygon, BigDecimal weight, BigDecimal macPct) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            EnvelopeVertex a = polygon.get(j);
            EnvelopeVertex b = polygon.get(i);
            if (onSegment(a, b, weight, macPct)) {
                return true;
            }
            boolean crosses = a.macPct().compareTo(macPct) > 0 != b.macPct().compareTo(macPct) > 0
                    && weight.compareTo(intersectX(a, b, macPct)) < 0;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 点 (x,y) 是否落在有向线段 a→b 上（含端点）。 */
    private static boolean onSegment(EnvelopeVertex a, EnvelopeVertex b, BigDecimal x, BigDecimal y) {
        BigDecimal cross = b.weight().subtract(a.weight()).multiply(y.subtract(a.macPct()))
                .subtract(b.macPct().subtract(a.macPct()).multiply(x.subtract(a.weight())));
        if (cross.abs().compareTo(EPS) > 0) {
            return false;
        }
        return x.compareTo(min(a.weight(), b.weight()).subtract(EPS)) >= 0
                && x.compareTo(max(a.weight(), b.weight()).add(EPS)) <= 0
                && y.compareTo(min(a.macPct(), b.macPct()).subtract(EPS)) >= 0
                && y.compareTo(max(a.macPct(), b.macPct()).add(EPS)) <= 0;
    }

    /** 水平射线与边交点的 x（重量）。 */
    private static BigDecimal intersectX(EnvelopeVertex a, EnvelopeVertex b, BigDecimal y) {
        BigDecimal dy = b.macPct().subtract(a.macPct());
        return b.weight().subtract(a.weight())
                .multiply(y.subtract(a.macPct()))
                .divide(dy, 10, java.math.RoundingMode.HALF_UP)
                .add(a.weight());
    }

    private static BigDecimal min(BigDecimal p, BigDecimal q) {
        return p.min(q);
    }

    private static BigDecimal max(BigDecimal p, BigDecimal q) {
        return p.max(q);
    }

    /**
     * 包线顶点。
     */
    public record EnvelopeVertex(BigDecimal weight, BigDecimal macPct) {
    }
}
