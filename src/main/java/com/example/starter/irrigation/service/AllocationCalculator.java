package com.example.starter.irrigation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 配水分配与欠供结转的纯函数计算器：
 * <ul>
 *   <li>权重 = 斗口面积 / 组内总面积，保留四位小数（HALF_UP）；</li>
 *   <li>分配结果取整到立方米，合计必须等于可供总量，差额归入面积最大的斗口
 *       （面积并列时取斗口编号最小者，保证确定性）；</li>
 *   <li>欠供结转上限为本轮计划量的 30%，恰满 30% 允许，超出部分作废；</li>
 *   <li>临时加购按欠供比例分配，差额归入欠供最大的斗口。</li>
 * </ul>
 */
public final class AllocationCalculator {

    /** 权重小数位数。 */
    public static final int WEIGHT_SCALE = 4;

    /** 结转上限比例（30%）。 */
    public static final BigDecimal CARRYOVER_CAP_RATIO = new BigDecimal("0.3");

    private AllocationCalculator() {
    }

    /**
     * 单个斗口的权重输入。
     *
     * @param outletId   斗口 id
     * @param outletCode 斗口编号
     * @param areaMu     灌溉面积（亩）
     */
    public record WeightedOutlet(long outletId, String outletCode, BigDecimal areaMu) {
    }

    /**
     * 单个斗口的欠供输入。
     *
     * @param outletId   斗口 id
     * @param outletCode 斗口编号
     * @param deficitM3  欠供水量（立方米），非负
     */
    public record DeficitOutlet(long outletId, String outletCode, long deficitM3) {
    }

    /**
     * 分配结果。
     *
     * @param outletId   斗口 id
     * @param outletCode 斗口编号
     * @param amountM3   分配水量（立方米）
     */
    public record Allocation(long outletId, String outletCode, long amountM3) {
    }

    /**
     * 按面积权重分配组内可供水量；返回顺序与输入一致，合计等于 {@code totalWaterM3}。
     */
    public static List<Allocation> allocateByArea(long totalWaterM3, List<WeightedOutlet> outlets) {
        if (outlets.isEmpty()) {
            throw new IllegalArgumentException("轮灌组至少需要一个斗口");
        }
        BigDecimal totalArea = outlets.stream()
                .map(WeightedOutlet::areaMu)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalArea.signum() <= 0) {
            throw new IllegalArgumentException("组内灌溉面积合计必须大于 0");
        }
        BigDecimal total = BigDecimal.valueOf(totalWaterM3);
        long[] amounts = new long[outlets.size()];
        long allocated = 0;
        for (int i = 0; i < outlets.size(); i++) {
            BigDecimal weight = outlets.get(i).areaMu().divide(totalArea, WEIGHT_SCALE, RoundingMode.HALF_UP);
            amounts[i] = total.multiply(weight).setScale(0, RoundingMode.HALF_UP).longValueExact();
            allocated += amounts[i];
        }
        // 差额归入面积最大的斗口（并列取斗口编号最小者），保证合计等于可供总量。
        int target = indexOfMax(outlets.size(),
                i -> outlets.get(i).areaMu(),
                Comparator.comparing(WeightedOutlet::outletCode, Comparator.naturalOrder())
                        .thenComparingLong(WeightedOutlet::outletId),
                outlets);
        amounts[target] += totalWaterM3 - allocated;
        List<Allocation> result = new ArrayList<>(outlets.size());
        for (int i = 0; i < outlets.size(); i++) {
            result.add(new Allocation(outlets.get(i).outletId(), outlets.get(i).outletCode(), amounts[i]));
        }
        return result;
    }

    /**
     * 按欠供比例分配临时加购水量；仅欠供大于 0 的斗口参与，合计等于 {@code amountM3}。
     */
    public static List<Allocation> allocateByDeficit(long amountM3, List<DeficitOutlet> deficits) {
        List<DeficitOutlet> owed = deficits.stream().filter(d -> d.deficitM3() > 0).toList();
        if (owed.isEmpty()) {
            throw new IllegalArgumentException("当前轮次没有欠供斗口，无法按欠供比例分配");
        }
        long totalDeficit = owed.stream().mapToLong(DeficitOutlet::deficitM3).sum();
        BigDecimal total = BigDecimal.valueOf(amountM3);
        BigDecimal divisor = BigDecimal.valueOf(totalDeficit);
        long[] amounts = new long[owed.size()];
        long allocated = 0;
        for (int i = 0; i < owed.size(); i++) {
            amounts[i] = total.multiply(BigDecimal.valueOf(owed.get(i).deficitM3()))
                    .divide(divisor, 0, RoundingMode.HALF_UP).longValueExact();
            allocated += amounts[i];
        }
        // 差额归入欠供最大的斗口（并列取斗口编号最小者）。
        int target = indexOfMax(owed.size(),
                i -> BigDecimal.valueOf(owed.get(i).deficitM3()),
                Comparator.comparing(DeficitOutlet::outletCode, Comparator.naturalOrder())
                        .thenComparingLong(DeficitOutlet::outletId),
                owed);
        amounts[target] += amountM3 - allocated;
        List<Allocation> result = new ArrayList<>(owed.size());
        for (int i = 0; i < owed.size(); i++) {
            result.add(new Allocation(owed.get(i).outletId(), owed.get(i).outletCode(), amounts[i]));
        }
        return result;
    }

    /**
     * 计算欠供结转水量：不超过本轮计划量的 30%（恰满允许），超出部分作废。
     *
     * @param plannedM3 本轮计划水量（立方米）
     * @param deficitM3 欠供水量（立方米），非负
     * @return 结转水量（立方米），取整到立方米（向下取整）
     */
    public static long carryover(long plannedM3, long deficitM3) {
        if (deficitM3 <= 0) {
            return 0;
        }
        BigDecimal cap = BigDecimal.valueOf(plannedM3).multiply(CARRYOVER_CAP_RATIO);
        BigDecimal deficit = BigDecimal.valueOf(deficitM3);
        if (deficit.compareTo(cap) <= 0) {
            return deficitM3;
        }
        return cap.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private static <T> int indexOfMax(int size,
                                      java.util.function.IntFunction<BigDecimal> metric,
                                      Comparator<T> tieBreaker,
                                      List<T> items) {
        int best = 0;
        for (int i = 1; i < size; i++) {
            int cmp = metric.apply(i).compareTo(metric.apply(best));
            if (cmp > 0 || (cmp == 0 && tieBreaker.compare(items.get(i), items.get(best)) < 0)) {
                best = i;
            }
        }
        return best;
    }
}
