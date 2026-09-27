package com.example.starter.fermentation.domain;

/**
 * 酵母单元状态：可用 → 投酵绑定中 →（回收）可用 / 报废。
 * SCRAPPED 为终态，不可再投酵。
 */
public enum YeastUnitStatus {

    /** 可用，可受控投酵；距最近回收超过 72 小时失效（恰 72 小时仍可用）。 */
    AVAILABLE,
    /** 已投酵绑定某批次，回收前不可再投酵。 */
    IN_USE,
    /** 报废（终态），不可再投酵。 */
    SCRAPPED
}
