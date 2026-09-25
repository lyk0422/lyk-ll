package com.example.starter.domain;

/**
 * 靠泊窗口状态机。
 *
 * <p>PENDING 待审批 → APPROVED 已批准 / REJECTED 驳回；
 * APPROVED 到达起始时刻自动转 ACTIVE 已生效；登记离泊后 COMPLETED 已完成；
 * PENDING/APPROVED 可被申请人取消为 CANCELLED。
 * REJECTED、CANCELLED、COMPLETED 为终态，不得复活。</p>
 */
public enum WindowStatus {

    /** 待审批。 */
    PENDING,
    /** 已批准。 */
    APPROVED,
    /** 已生效（到达起始时刻）。 */
    ACTIVE,
    /** 已驳回（终态）。 */
    REJECTED,
    /** 已取消（终态）。 */
    CANCELLED,
    /** 已完成（终态，已登记离泊）。 */
    COMPLETED;

    /**
     * 是否为终态。
     */
    public boolean isTerminal() {
        return this == REJECTED || this == CANCELLED || this == COMPLETED;
    }
}
