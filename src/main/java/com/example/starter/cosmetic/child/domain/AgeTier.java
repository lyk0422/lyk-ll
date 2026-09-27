package com.example.starter.cosmetic.child.domain;

/**
 * 儿童分龄档位（合成规则，整数月闭区间）。
 *
 * <p>0～36 月为低龄档，37～144 月为大龄档；申请区间跨档（如下限≤36 且上限≥37）
 * 须两档资料齐全。</p>
 */
public enum AgeTier {

    /** 0～36 月龄档（含端点）。 */
    TIER_0_36(0, 36),
    /** 37～144 月龄档（含端点）。 */
    TIER_37_144(37, 144);

    private final int fromMonths;
    private final int toMonths;

    AgeTier(int fromMonths, int toMonths) {
        this.fromMonths = fromMonths;
        this.toMonths = toMonths;
    }

    /** 该档年龄下限（整数月，闭区间）。 */
    public int fromMonths() {
        return fromMonths;
    }

    /** 该档年龄上限（整数月，闭区间）。 */
    public int toMonths() {
        return toMonths;
    }

    /**
     * 给定申请年龄闭区间 [from, to] 是否与本档相交（命中本档）。
     * 跨档判定：区间与档位有任一整数月重叠即命中。
     */
    public boolean covers(int from, int to) {
        return from <= toMonths && to >= fromMonths;
    }
}
