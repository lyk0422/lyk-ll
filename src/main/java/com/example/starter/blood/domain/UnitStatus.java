package com.example.starter.blood.domain;

/**
 * 成分血单位状态机。
 *
 * <p>流转规则：入库为 {@link #PENDING 待检}；初检/复检可乱序回传，
 * 一项合格一项未回为 {@link #ISOLATED 隔离}，两项均合格为 {@link #RELEASABLE 可放行}；
 * 任一项不合格直接进入 {@link #DISCARDED 报废} 终态；放行成功进入 {@link #RELEASED 已放行} 终态；
 * 超过效期（UTC）进入 {@link #EXPIRED_DISCARDED 过期报废} 终态。终态不可复活。
 */
public enum UnitStatus {

    /** 待检：初检与复检均未回传。 */
    PENDING,

    /** 隔离：恰有一项检测合格、另一项未回传。 */
    ISOLATED,

    /** 可放行：初检与复检均合格。 */
    RELEASABLE,

    /** 已放行：放行成功并完成发血（终态）。 */
    RELEASED,

    /** 报废：任一检测项不合格（终态）。 */
    DISCARDED,

    /** 过期报废：超过效期截止时刻（UTC），与人工报废口径一致（终态）。 */
    EXPIRED_DISCARDED;

    /**
     * 判断当前状态是否为终态（不再接受检测结果改变状态）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == RELEASED || this == DISCARDED || this == EXPIRED_DISCARDED;
    }
}
