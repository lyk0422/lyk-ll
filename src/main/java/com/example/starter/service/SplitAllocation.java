package com.example.starter.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 分缸耗用分摊：纯计算工具，不访问数据库。
 * 每种染料按各子缸布重比例分摊来源实际耗用，先向下截到 0.0001 克，
 * 剩余 0.0001 克单位按子缸编号升序循环分配，保证分摊合计精确等于来源实际耗用。
 */
public final class SplitAllocation {

    /** 分摊最小单位（克）：0.0001g，余量以该单位循环分配。 */
    public static final BigDecimal UNIT = new BigDecimal("0.0001");
    private static final int UNIT_SCALE = 4;

    private SplitAllocation() {
    }

    /**
     * 分摊一种染料的来源实际耗用。
     *
     * <p>以 0.0001g 为最小整数单位：每子缸先取 floor(总量 × 子缸布重 / 布重合计) 单位，
     * 剩余单位从编号 1 起循环（每轮按编号升序）逐单位补发，故合计精确等于来源实际耗用，
     * 且各子缸所得均为 0.0001g 的整数倍。
     *
     * @param totalActualG 来源该染料实际耗用（克，0.0001 的整数倍）
     * @param childWeightsKg 各子缸布重（公斤），列表顺序即子缸编号升序
     * @return 与 childWeightsKg 同序的各子缸分摊克重（0.0001 的整数倍，合计等于 totalActualG）
     */
    public static List<BigDecimal> allocate(BigDecimal totalActualG, List<BigDecimal> childWeightsKg) {
        if (totalActualG == null || totalActualG.signum() < 0) {
            throw new IllegalArgumentException("来源实际耗用不能为空或负数");
        }
        if (childWeightsKg == null || childWeightsKg.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个子缸布重");
        }
        BigInteger totalUnits;
        try {
            totalUnits = totalActualG.setScale(UNIT_SCALE).movePointRight(UNIT_SCALE).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("来源实际耗用 " + totalActualG + "g 不是 0.0001g 的整数倍");
        }
        BigDecimal weightSum = BigDecimal.ZERO;
        for (BigDecimal w : childWeightsKg) {
            if (w == null || w.signum() <= 0) {
                throw new IllegalArgumentException("子缸布重必须为正数");
            }
            weightSum = weightSum.add(w);
        }
        BigDecimal totalDecimal = new BigDecimal(totalUnits);
        List<BigInteger> units = new ArrayList<>(childWeightsKg.size());
        BigInteger assigned = BigInteger.ZERO;
        for (BigDecimal w : childWeightsKg) {
            // 精确十进制除法向下取整（均为正数，FLOOR==DOWN）。
            BigInteger floorUnits = totalDecimal.multiply(w)
                    .divide(weightSum, 0, java.math.RoundingMode.FLOOR).toBigIntegerExact();
            units.add(floorUnits);
            assigned = assigned.add(floorUnits);
        }
        // 剩余 0.0001g 单位按子缸编号升序循环分配，直至分光。
        BigInteger remainder = totalUnits.subtract(assigned);
        for (int idx = 0; remainder.signum() > 0; idx = (idx + 1) % units.size()) {
            units.set(idx, units.get(idx).add(BigInteger.ONE));
            remainder = remainder.subtract(BigInteger.ONE);
        }
        List<BigDecimal> result = new ArrayList<>(units.size());
        for (BigInteger u : units) {
            result.add(new BigDecimal(u).movePointLeft(UNIT_SCALE).setScale(UNIT_SCALE));
        }
        return result;
    }
}
