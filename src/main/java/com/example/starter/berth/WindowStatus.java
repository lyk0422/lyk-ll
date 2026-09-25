package com.example.starter.berth;

/**
 * 靠泊窗口状态机。
 * <ul>
 *   <li>PENDING 待审批（申请创建后的初始状态）</li>
 *   <li>APPROVED 已批准（审批通过，等待到达起始时刻）</li>
 *   <li>ACTIVE 已生效（到达起始时刻自动转换）</li>
 *   <li>REJECTED 已驳回（终态）</li>
 *   <li>CANCELLED 已取消（终态）</li>
 *   <li>COMPLETED 已完成（终态，登记离泊后）</li>
 * </ul>
 */
public enum WindowStatus {
    PENDING,
    APPROVED,
    ACTIVE,
    REJECTED,
    CANCELLED,
    COMPLETED;

    /**
     * 是否为终态（驳回、取消、完成）。终态窗口不得被迟到操作复活。
     */
    public boolean isTerminal() {
        return this == REJECTED || this == CANCELLED || this == COMPLETED;
    }
}
