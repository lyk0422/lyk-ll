package com.example.starter.catalog;

import java.math.BigDecimal;

/**
 * 检验项目单项判定：所有比较均含等号边界，恰等限值判合格。
 */
public final class InspectionEvaluator {

    private InspectionEvaluator() {
    }

    /**
     * 按规格复算单项实测值是否合格。
     *
     * @param spec          项目规格
     * @param measuredValue 实测值文本
     * @return true 表示合格；无法解析的数值或文本不符均判不合格
     */
    public static boolean isQualified(InspectionSpec spec, String measuredValue) {
        if (measuredValue == null) {
            return false;
        }
        String raw = measuredValue.trim();
        switch (spec.comparison()) {
            case TEXT_PASS -> {
                return spec.passText().equals(raw);
            }
            case GTE -> {
                BigDecimal value = parse(raw);
                return value != null && value.compareTo(spec.limitMin()) >= 0;
            }
            case LTE -> {
                BigDecimal value = parse(raw);
                return value != null && value.compareTo(spec.limitMax()) <= 0;
            }
            case RANGE -> {
                BigDecimal value = parse(raw);
                return value != null
                        && value.compareTo(spec.limitMin()) >= 0
                        && value.compareTo(spec.limitMax()) <= 0;
            }
            default -> {
                return false;
            }
        }
    }

    private static BigDecimal parse(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
