package com.example.starter.domain;

/**
 * 成分血单位状态机。
 * PENDING_TEST 待检 → （任一结果回传） → QUARANTINED 隔离 / DISCARDED 报废；
 * QUARANTINED → RELEASABLE 可放行 / DISCARDED 报废；
 * RELEASABLE → RELEASED 已放行 / EXPIRED_DISCARDED 过期报废；
 * DISCARDED、EXPIRED_DISCARDED、RELEASED 为终态。
 */
public enum UnitStatus {
    /** 待检：入库后尚无任何检测结果回传。 */
    PENDING_TEST,
    /** 隔离：一项合格、另一项未回传。 */
    QUARANTINED,
    /** 可放行：初检与复检均合格。 */
    RELEASABLE,
    /** 已放行：放行扣减完成并生成发血记录（终态）。 */
    RELEASED,
    /** 报废：任一检测项不合格（终态，不可复活）。 */
    DISCARDED,
    /** 过期报废：超过效期边界仍未放行（终态，与人工报废口径一致）。 */
    EXPIRED_DISCARDED;

    /**
     * 判断当前状态是否为终态（不再接受任何状态迁移）。
     */
    public boolean isTerminal() {
        return this == RELEASED || this == DISCARDED || this == EXPIRED_DISCARDED;
    }
}
