package com.example.starter.model;

/**
 * 检修单状态：已登记 / 已取消 / 已完成。
 */
public enum MaintenanceOrderStatus {
    /** 已登记：到达窗口起点即生效阻断，直至取消或验收完成 */
    SCHEDULED,
    /** 已取消：仅未生效单可取消，终态 */
    CANCELLED,
    /** 已完成：分段清空且机务、检修双岗验收齐全，终态 */
    COMPLETED
}
