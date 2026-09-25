package com.example.starter.domain;

/**
 * 成分血单位状态。
 */
public enum UnitStatus {

    /** 待检：已入库，尚无任何检测结果回传。 */
    PENDING,
    /** 隔离：一项检测合格、另一项尚未回传。 */
    QUARANTINED,
    /** 可放行：初检与复检均合格。 */
    RELEASABLE,
    /** 已放行：已完成发血，终态。 */
    RELEASED,
    /** 报废：任一检测不合格或人工报废，终态。 */
    SCRAPPED,
    /** 过期报废：超过效期边界仍未放行，与人工报废口径一致，终态。 */
    EXPIRED;

    /**
     * 是否为终态：终态单位不再因检测回传或放行请求复活。
     */
    public boolean isTerminal() {
        return this == RELEASED || this == SCRAPPED || this == EXPIRED;
    }
}
