package com.example.starter.catalog;

import java.math.BigDecimal;

/**
 * 单项检验项目的合成限值规格。恰等限值判合格（比较均含等号边界）。
 *
 * @param itemCode  项目代码
 * @param itemName  项目名称
 * @param critical  是否关键项目（效力、无菌、异常毒性）
 * @param comparison 判定方式 GTE/LTE/EQ/RANGE/TEXT_PASS
 * @param limitMin  数值下限时的下界（含），定性项目为 null
 * @param limitMax  RANGE 上限（含），其余方式为 null
 * @param passText  TEXT_PASS 判定时视为合格的文本（如“无菌”）
 */
public record InspectionSpec(
        String itemCode,
        String itemName,
        boolean critical,
        Comparison comparison,
        BigDecimal limitMin,
        BigDecimal limitMax,
        String passText
) {

    /** 判定方式。 */
    public enum Comparison {
        /** 实测值 ≥ 下界（含等号）。 */
        GTE,
        /** 实测值 ≤ 上界（含等号）。 */
        LTE,
        /** 数值区间，上下界均含等号。 */
        RANGE,
        /** 定性文本与合格文本一致。 */
        TEXT_PASS
    }

    public static InspectionSpec gte(String code, String name, boolean critical, BigDecimal min) {
        return new InspectionSpec(code, name, critical, Comparison.GTE, min, null, null);
    }

    public static InspectionSpec lte(String code, String name, boolean critical, BigDecimal max) {
        return new InspectionSpec(code, name, critical, Comparison.LTE, null, max, null);
    }

    public static InspectionSpec range(String code, String name, boolean critical,
                                       BigDecimal min, BigDecimal max) {
        return new InspectionSpec(code, name, critical, Comparison.RANGE, min, max, null);
    }

    public static InspectionSpec text(String code, String name, boolean critical, String passText) {
        return new InspectionSpec(code, name, critical, Comparison.TEXT_PASS, null, null, passText);
    }
}
