package com.example.starter.domain;

/**
 * 风险抽检等级（合成规则，不用于生产）：普通抽检 10%、加严抽检 30%。
 * 应抽数量为头只数乘比例向上取整且至少 1。
 */
public enum RiskLevel {
    /** 普通抽检，比例 10%。 */
    NORMAL(10),
    /** 加严抽检，比例 30%。 */
    STRICT(30);

    private final int ratioPercent;

    RiskLevel(int ratioPercent) {
        this.ratioPercent = ratioPercent;
    }

    /** 抽检比例（百分比整数）。 */
    public int ratioPercent() {
        return ratioPercent;
    }

    /** 应抽头只数：头只数乘比例向上取整且至少 1（整数运算，避免浮点误差）。 */
    public int requiredCount(int headCount) {
        return Math.max(1, (headCount * ratioPercent + 99) / 100);
    }
}
