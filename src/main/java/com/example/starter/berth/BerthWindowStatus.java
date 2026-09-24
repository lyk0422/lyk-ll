package com.example.starter.berth;

/**
 * 靠泊窗口状态机。
 * PENDING 待审批；APPROVED 已批准；ACTIVE 已生效（到达起始时刻自动转换）；
 * COMPLETED 已完成（登记离泊）；REJECTED 已驳回；CANCELLED 已取消。
 * REJECTED、CANCELLED、COMPLETED 为终态，迟到操作不得复活。
 */
public enum BerthWindowStatus {
    PENDING,
    APPROVED,
    ACTIVE,
    COMPLETED,
    REJECTED,
    CANCELLED;

    /**
     * 是否为终态（驳回、取消、完成）。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == CANCELLED;
    }
}
