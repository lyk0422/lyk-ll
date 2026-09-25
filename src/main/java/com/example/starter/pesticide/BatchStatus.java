package com.example.starter.pesticide;

/**
 * 批次状态机：REPORTED 已报备 → SAMPLED 已抽样 → FROZEN 疑似冻结 / RELEASED 放行（终态）/ DISPOSED 处置结案（终态）。
 */
public enum BatchStatus {
    /** 已报备，待抽样 */
    REPORTED,
    /** 已抽样，可快检与判定放行 */
    SAMPLED,
    /** 疑似超标冻结，待定量复检 */
    FROZEN,
    /** 准入放行（终态） */
    RELEASED,
    /** 监督处置结案（终态） */
    DISPOSED;

    /**
     * 是否为终态（放行或处置结案）。
     */
    public boolean isTerminal() {
        return this == RELEASED || this == DISPOSED;
    }
}
