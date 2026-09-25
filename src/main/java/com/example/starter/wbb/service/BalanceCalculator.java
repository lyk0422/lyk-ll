package com.example.starter.wbb.service;

import com.example.starter.wbb.domain.PayloadCategory;
import com.example.starter.wbb.dto.PayloadItemDto;
import com.example.starter.wbb.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 载重与平衡纯计算：重量、力矩、重心力臂、包线判定与 LMC 限额判定。
 * <p>重量 kg、力臂 m、力矩 kg·m；恰达重量上限与恰在包线边界均判合法。
 * 所有计算可由业载明细复算重现。
 */
public final class BalanceCalculator {

    /** 业载/重心结果保留 6 位小数（力臂），重量 2 位。 */
    static final int ARM_SCALE = 6;
    static final int WEIGHT_SCALE = 2;
    static final int MOMENT_SCALE = 2;

    /** LMC 人数限额（恰达含）。 */
    public static final int LMC_MAX_PAX = 5;
    /** LMC 重量限额 kg（恰达含）。 */
    public static final BigDecimal LMC_MAX_KG = new BigDecimal("500");

    private BalanceCalculator() {
    }

    /**
     * 业载汇总结果。
     */
    public record PayloadTotals(BigDecimal weightKg, BigDecimal moment, int passengerCount) {
    }

    /**
     * 汇总业载明细：力矩 = 重量 × 力臂；PAX 行人数累加。
     */
    public static PayloadTotals sumPayload(List<PayloadItemDto> items) {
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal moment = BigDecimal.ZERO;
        int pax = 0;
        if (items != null) {
            for (PayloadItemDto item : items) {
                PayloadCategory.valueOf(item.category());
                if (item.pieceCount() == null || item.weightKg() == null || item.arm() == null) {
                    throw new BusinessRuleException("业载明细件数/重量/力臂不得为空");
                }
                weight = weight.add(item.weightKg());
                moment = moment.add(item.weightKg().multiply(item.arm()));
                if (PayloadCategory.PAX.name().equals(item.category())) {
                    pax += item.pieceCount();
                }
            }
        }
        return new PayloadTotals(
                weight.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP),
                moment.setScale(MOMENT_SCALE, RoundingMode.HALF_UP),
                pax);
    }

    /**
     * 无油重心力臂 = 无油力矩 / 无油重量。
     */
    public static BigDecimal cgArm(BigDecimal moment, BigDecimal weight) {
        if (weight.signum() == 0) {
            throw new BusinessRuleException("重量为 0，无法计算重心力臂");
        }
        return moment.divide(weight, ARM_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 业载是否满足最大无油重量：ZFW = OEW + 业载 ≤ MZFW，恰等合法。
     */
    public static boolean withinZeroFuelLimit(BigDecimal payloadKg,
                                              BigDecimal operatingEmptyWeight,
                                              BigDecimal maxZeroFuelWeight) {
        BigDecimal zfw = operatingEmptyWeight.add(payloadKg);
        return zfw.compareTo(maxZeroFuelWeight) <= 0;
    }

    /**
     * 包线折点。
     */
    public record EnvelopePoint(BigDecimal weightKg, BigDecimal forwardArm, BigDecimal aftArm) {
    }

    /**
     * 判定给定重量/重心是否落在包线内（含边界）。包线按重量升序线性插值前后限；
     * 重量超出折点重量区间则判越界（不做外推），恰在前/后限上合法。
     */
    public static boolean withinEnvelope(BigDecimal weightKg, BigDecimal cgArm, List<EnvelopePoint> points) {
        if (points == null || points.size() < 2) {
            throw new BusinessRuleException("重心包线至少需要 2 个折点");
        }
        List<EnvelopePoint> sorted = points.stream()
                .sorted((a, b) -> a.weightKg().compareTo(b.weightKg()))
                .toList();
        EnvelopePoint first = sorted.get(0);
        EnvelopePoint last = sorted.get(sorted.size() - 1);
        if (weightKg.compareTo(first.weightKg()) < 0 || weightKg.compareTo(last.weightKg()) > 0) {
            return false;
        }
        for (int i = 0; i < sorted.size() - 1; i++) {
            EnvelopePoint lo = sorted.get(i);
            EnvelopePoint hi = sorted.get(i + 1);
            if (weightKg.compareTo(lo.weightKg()) >= 0 && weightKg.compareTo(hi.weightKg()) <= 0) {
                BigDecimal ratio = weightKg.subtract(lo.weightKg());
                BigDecimal span = hi.weightKg().subtract(lo.weightKg());
                BigDecimal t = span.signum() == 0
                        ? BigDecimal.ZERO
                        : ratio.divide(span, 10, RoundingMode.HALF_UP);
                BigDecimal forward = lo.forwardArm().add(
                        hi.forwardArm().subtract(lo.forwardArm()).multiply(t))
                        .setScale(ARM_SCALE, RoundingMode.HALF_UP);
                BigDecimal aft = lo.aftArm().add(
                        hi.aftArm().subtract(lo.aftArm()).multiply(t))
                        .setScale(ARM_SCALE, RoundingMode.HALF_UP);
                return cgArm.compareTo(forward) >= 0 && cgArm.compareTo(aft) <= 0;
            }
        }
        return false;
    }

    /**
     * LMC 是否在限额内：|人数增量| ≤ 5 且 |重量增量| ≤ 500，恰达含。
     */
    public static boolean withinLmcLimit(int deltaPax, BigDecimal deltaKg) {
        return Math.abs(deltaPax) <= LMC_MAX_PAX
                && deltaKg.abs().compareTo(LMC_MAX_KG) <= 0;
    }
}
