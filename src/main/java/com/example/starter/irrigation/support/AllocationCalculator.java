package com.example.starter.irrigation.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 组内配水纯计算逻辑，不依赖数据库与 Spring。
 *
 * <p>权重规则：按各斗口灌溉面积占组内总面积的比例计算权重，保留四位小数（四舍五入）。
 * 分配规则：各斗口先取 {@code floor(权重 × 可供总量)} 立方米，取整差额全部归入面积最大的斗口
 * （面积并列时取斗口编号较小者），保证分配合计恰好等于可供总量。
 */
public final class AllocationCalculator {

    /** 斗口配水输入。 */
    public record OutletArea(String outletCode, long areaSquareMeter) {}

    /** 斗口配水结果，单位均为立方米（权重为无量纲四位小数）。 */
    public record Allocation(String outletCode, long areaSquareMeter, BigDecimal weight, long planCubicMeter) {}

    private AllocationCalculator() {
    }

    /**
     * 按面积权重分配可供水量。
     *
     * @param outlets        组内斗口（灌溉面积必须为正）
     * @param totalAvailable 组内可供水量，单位立方米，必须非负
     * @return 各斗口计划量（按斗口编号升序），合计严格等于可供总量
     */
    public static List<Allocation> allocateByArea(List<OutletArea> outlets, long totalAvailable) {
        if (outlets == null || outlets.isEmpty()) {
            throw new IllegalArgumentException("轮灌组内至少需要一个斗口");
        }
        if (totalAvailable < 0) {
            throw new IllegalArgumentException("可供水量不能为负");
        }
        long totalArea = 0;
        for (OutletArea outlet : outlets) {
            if (outlet.areaSquareMeter() <= 0) {
                throw new IllegalArgumentException("斗口灌溉面积必须为正: " + outlet.outletCode());
            }
            totalArea += outlet.areaSquareMeter();
        }

        // 面积最大者（并列取编号较小）承担取整差额。
        String remainderOwner = outlets.stream()
                .max(Comparator.comparingLong(OutletArea::areaSquareMeter)
                        .thenComparing(OutletArea::outletCode, Comparator.reverseOrder()))
                .orElseThrow()
                .outletCode();

        List<Allocation> results = new ArrayList<>();
        long allocatedSum = 0;
        for (OutletArea outlet : outlets) {
            BigDecimal weight = BigDecimal.valueOf(outlet.areaSquareMeter())
                    .divide(BigDecimal.valueOf(totalArea), 4, RoundingMode.HALF_UP);
            long plan = weight.multiply(BigDecimal.valueOf(totalAvailable))
                    .setScale(0, RoundingMode.FLOOR)
                    .longValueExact();
            allocatedSum += plan;
            results.add(new Allocation(outlet.outletCode(), outlet.areaSquareMeter(), weight, plan));
        }

        long remainder = totalAvailable - allocatedSum;
        if (remainder < 0) {
            throw new IllegalStateException("取整后分配合计超过可供总量");
        }
        List<Allocation> finalResults = new ArrayList<>();
        for (Allocation allocation : results) {
            if (allocation.outletCode().equals(remainderOwner)) {
                finalResults.add(new Allocation(
                        allocation.outletCode(), allocation.areaSquareMeter(),
                        allocation.weight(), allocation.planCubicMeter() + remainder));
            } else {
                finalResults.add(allocation);
            }
        }
        finalResults.sort(Comparator.comparing(Allocation::outletCode));
        return finalResults;
    }

    /**
     * 计算单个斗口欠供可结转量：结转上限为该斗口本轮计划量的 30%（按立方米向下取整），
     * 恰满 30% 允许，超出上限的欠供部分作废。
     *
     * @param planCubicMeter     本轮计划量，立方米
     * @param shortageCubicMeter 本轮欠供量（计划减实供，为负表示超供，按 0 处理），立方米
     * @return 可结转至下一轮的补供量，立方米
     */
    public static long carryover(long planCubicMeter, long shortageCubicMeter) {
        if (planCubicMeter < 0 || shortageCubicMeter < 0) {
            throw new IllegalArgumentException("计划量与欠供量不能为负");
        }
        long limit = BigDecimal.valueOf(planCubicMeter)
                .multiply(BigDecimal.valueOf(30))
                .movePointLeft(2)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
        return Math.min(shortageCubicMeter, limit);
    }
}
