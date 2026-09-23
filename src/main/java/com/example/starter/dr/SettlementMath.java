package com.example.starter.dr;

import java.math.BigDecimal;

/**
 * 需求响应结算计算工具：单区间交付量 = min(承诺值, max(0, 基线-实测))。
 */
public final class SettlementMath {

    private SettlementMath() {
    }

    /**
     * 计算单区间交付削减量（kW）。
     *
     * @param baseline 基线功率（kW，非负），为空表示缺值，不得按零结算
     * @param actual   实测功率（kW，非负），为空表示缺值，不得按零结算
     * @param committed 承诺削减功率（kW，非负），交付量以此封顶
     * @return 交付量；基线或实测缺值时返回 {@code null}（PENDING）
     */
    public static BigDecimal delivered(BigDecimal baseline, BigDecimal actual, BigDecimal committed) {
        if (baseline == null || actual == null) {
            return null;
        }
        BigDecimal reduction = baseline.subtract(actual);
        if (reduction.signum() < 0) {
            reduction = BigDecimal.ZERO;
        }
        return reduction.compareTo(committed) > 0 ? committed : reduction;
    }
}
