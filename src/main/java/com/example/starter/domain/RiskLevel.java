package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 合成风险等级：普通抽检比例 10%，加严抽检比例 30%。
 * 抽检头只数为头只总数乘比例后向上取整，且至少 1 只。
 */
public enum RiskLevel {
    /** 普通抽检：10%，向上取整且至少 1 只。 */
    NORMAL(new BigDecimal("0.10")),
    /** 加严抽检：30%，向上取整且至少 1 只。 */
    TIGHTENED(new BigDecimal("0.30"));

    private final BigDecimal ratio;

    RiskLevel(BigDecimal ratio) {
        this.ratio = ratio;
    }

    /** 冻结比例（0.10 / 0.30）。 */
    public BigDecimal ratio() {
        return ratio;
    }

    /** 按比例计算抽检头只数：ceil(总数 × 比例)，至少 1 只；整数运算避免浮点误差。 */
    public int sampleSize(int headCount) {
        int scaled = switch (this) {
            case NORMAL -> (headCount + 9) / 10;
            case TIGHTENED -> (3 * headCount + 9) / 10;
        };
        return Math.max(1, scaled);
    }

    /** 等级序号，仅允许提高（NORMAL &lt; TIGHTENED）。 */
    public int rank() {
        return ordinal();
    }
}
