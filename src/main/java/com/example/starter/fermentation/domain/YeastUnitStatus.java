package com.example.starter.fermentation.domain;

/**
 * 酵母单元状态：可用 → 主酵占用 → 回收后可用 / 报废 / 失效。
 * RETIRED 与 EXPIRED 为不可再投酵状态。
 */
public enum YeastUnitStatus {

    /** 可用，可投酵（需同时满足代次 ≤5 且距最近回收不超过 72 小时）。 */
    AVAILABLE,
    /** 正被一个主酵批次占用，同一时刻仅允许一个批次。 */
    IN_USE,
    /** 报废（回收活率不足 90% 或检出污染），不可再投酵。 */
    RETIRED,
    /** 回收后超过 72 小时未再投酵而失效，不可再投酵。 */
    EXPIRED
}
