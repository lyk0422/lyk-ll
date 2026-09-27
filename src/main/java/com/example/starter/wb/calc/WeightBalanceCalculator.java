package com.example.starter.wb.calc;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.EnvelopePoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 载重与平衡纯计算：业载合计、ZFW/TOW、力矩与 %MAC 重心，
 * 并按“总业载 ≤ MZFW−OEW（恰等合法）、重心在包线内（恰边界合法）”判定。
 * 所有结果可由明细完整复算。
 */
public final class WeightBalanceCalculator {

    private static final int CG_SCALE = 4;
    private static final int MOMENT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 英寸到毫米换算：×25.4 为精确十进制乘法，装配力矩全程保持 kg·mm 精确求和。 */
    private static final BigDecimal MM_PER_INCH = new BigDecimal("25.4");

    private WeightBalanceCalculator() {
    }

    public static WeightBalanceResult calculate(Aircraft ac, List<CalcLoadItem> items, BigDecimal fuelKg) {
        BigDecimal payload = BigDecimal.ZERO.setScale(MOMENT_SCALE);
        BigDecimal payloadMoment = BigDecimal.ZERO.setScale(MOMENT_SCALE);
        int pax = 0;
        for (CalcLoadItem item : items) {
            payload = payload.add(item.weightKg());
            payloadMoment = payloadMoment.add(item.moment());
            if (item.isPassenger()) {
                pax += item.pieces();
            }
        }

        BigDecimal oew = BigDecimal.valueOf(ac.oewKg());
        BigDecimal fuel = fuelKg.setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
        BigDecimal zfw = oew.add(payload);
        BigDecimal tow = zfw.add(fuel);

        BigDecimal zfwMoment = oew.multiply(ac.oewArm()).add(payloadMoment);
        BigDecimal towMoment = zfwMoment.add(fuel.multiply(ac.fuelArm()));

        BigDecimal cgZfw = toMacPct(ac, zfwMoment.divide(zfw, 6, RoundingMode.HALF_UP));
        BigDecimal cgTow = toMacPct(ac, towMoment.divide(tow, 6, RoundingMode.HALF_UP));

        List<String> violations = new ArrayList<>();
        BigDecimal payloadLimit = BigDecimal.valueOf(ac.mzfwKg() - ac.oewKg());
        if (payload.compareTo(payloadLimit) > 0) {
            violations.add("总业载 " + payload + "kg 超过上限 MZFW-OEW=" + payloadLimit + "kg");
        }
        if (zfw.compareTo(BigDecimal.valueOf(ac.mzfwKg())) > 0) {
            violations.add("ZFW " + zfw + "kg 超过 MZFW " + ac.mzfwKg() + "kg");
        }
        if (tow.compareTo(BigDecimal.valueOf(ac.mtowKg())) > 0) {
            violations.add("TOW " + tow + "kg 超过 MTOW " + ac.mtowKg() + "kg");
        }
        if (fuel.compareTo(BigDecimal.ZERO) < 0) {
            violations.add("加油量不得为负");
        }
        List<EnvelopeChecker.EnvelopeVertex> polygon = toPolygon(ac.envelope());
        if (!EnvelopeChecker.contains(polygon, zfw, cgZfw)) {
            violations.add("无油重心 " + cgZfw + "%MAC @ " + zfw + "kg 超出包线");
        }
        if (!EnvelopeChecker.contains(polygon, tow, cgTow)) {
            violations.add("起飞重心 " + cgTow + "%MAC @ " + tow + "kg 超出包线");
        }

        return new WeightBalanceResult(
                pax,
                payload,
                fuel,
                zfw,
                tow,
                zfwMoment.setScale(MOMENT_SCALE, RoundingMode.HALF_UP),
                towMoment.setScale(MOMENT_SCALE, RoundingMode.HALF_UP),
                cgZfw,
                cgTow,
                violations.isEmpty(),
                List.copyOf(violations)
        );
    }

    /** 力臂（站位英寸）换算为 %MAC：(arm−LEMAC)/MAC×100。 */
    private static BigDecimal toMacPct(Aircraft ac, BigDecimal arm) {
        return arm.subtract(ac.lemac())
                .divide(ac.macLength(), 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(CG_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 集装器装配模式计算：货运业载不自站位明细取力臂，而以装配图精确力矩（kg·mm）参与；
     * 非货运业载与空机、燃油力臂由英寸精确换算毫米（×25.4），全部力矩以 kg·mm 精确求和后，
     * 仍按既有重量限制与重心包线规则判定（边界合法）。
     *
     * @param nonCargoItems   非货运业载（旅客/行李）计算项，力臂为站位英寸
     * @param cargoWeightKg   装配图总货重（含皮重），kg
     * @param cargoMomentKgMm 装配图货运力矩 Σ(总重×力臂)，kg·mm，精确整数
     */
    public static WeightBalanceResult calculateWithCargoMoment(
            Aircraft ac, List<CalcLoadItem> nonCargoItems,
            BigDecimal cargoWeightKg, BigDecimal cargoMomentKgMm, BigDecimal fuelKg) {
        BigDecimal payload = cargoWeightKg.setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
        BigDecimal payloadMomentMm = cargoMomentKgMm;
        int pax = 0;
        for (CalcLoadItem item : nonCargoItems) {
            payload = payload.add(item.weightKg());
            payloadMomentMm = payloadMomentMm.add(item.moment().multiply(MM_PER_INCH));
            if (item.isPassenger()) {
                pax += item.pieces();
            }
        }

        BigDecimal oew = BigDecimal.valueOf(ac.oewKg());
        BigDecimal fuel = fuelKg.setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
        BigDecimal zfw = oew.add(payload);
        BigDecimal tow = zfw.add(fuel);

        BigDecimal zfwMomentMm = oew.multiply(ac.oewArm()).multiply(MM_PER_INCH).add(payloadMomentMm);
        BigDecimal towMomentMm = zfwMomentMm.add(fuel.multiply(ac.fuelArm()).multiply(MM_PER_INCH));

        BigDecimal cgZfw = toMacPctMm(ac, zfwMomentMm.divide(zfw, 6, RoundingMode.HALF_UP));
        BigDecimal cgTow = toMacPctMm(ac, towMomentMm.divide(tow, 6, RoundingMode.HALF_UP));

        List<String> violations = new ArrayList<>();
        BigDecimal payloadLimit = BigDecimal.valueOf(ac.mzfwKg() - ac.oewKg());
        if (payload.compareTo(payloadLimit) > 0) {
            violations.add("总业载 " + payload + "kg 超过上限 MZFW-OEW=" + payloadLimit + "kg");
        }
        if (zfw.compareTo(BigDecimal.valueOf(ac.mzfwKg())) > 0) {
            violations.add("ZFW " + zfw + "kg 超过 MZFW " + ac.mzfwKg() + "kg");
        }
        if (tow.compareTo(BigDecimal.valueOf(ac.mtowKg())) > 0) {
            violations.add("TOW " + tow + "kg 超过 MTOW " + ac.mtowKg() + "kg");
        }
        if (fuel.compareTo(BigDecimal.ZERO) < 0) {
            violations.add("加油量不得为负");
        }
        List<EnvelopeChecker.EnvelopeVertex> polygon = toPolygon(ac.envelope());
        if (!EnvelopeChecker.contains(polygon, zfw, cgZfw)) {
            violations.add("无油重心 " + cgZfw + "%MAC @ " + zfw + "kg 超出包线");
        }
        if (!EnvelopeChecker.contains(polygon, tow, cgTow)) {
            violations.add("起飞重心 " + cgTow + "%MAC @ " + tow + "kg 超出包线");
        }

        return new WeightBalanceResult(
                pax,
                payload,
                fuel,
                zfw,
                tow,
                zfwMomentMm.setScale(MOMENT_SCALE, RoundingMode.HALF_UP),
                towMomentMm.setScale(MOMENT_SCALE, RoundingMode.HALF_UP),
                cgZfw,
                cgTow,
                violations.isEmpty(),
                List.copyOf(violations)
        );
    }

    /** 毫米力臂换算为 %MAC：与英寸路径数学等价（换算系数在比值中约去），保持装配力矩精确。 */
    private static BigDecimal toMacPctMm(Aircraft ac, BigDecimal armMm) {
        BigDecimal lemacMm = ac.lemac().multiply(MM_PER_INCH);
        BigDecimal macLengthMm = ac.macLength().multiply(MM_PER_INCH);
        return armMm.subtract(lemacMm)
                .divide(macLengthMm, 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(CG_SCALE, RoundingMode.HALF_UP);
    }

    private static List<EnvelopeChecker.EnvelopeVertex> toPolygon(List<EnvelopePoint> points) {
        return points.stream()
                .map(p -> new EnvelopeChecker.EnvelopeVertex(
                        BigDecimal.valueOf(p.weightKg()), p.macPct()))
                .toList();
    }
}
