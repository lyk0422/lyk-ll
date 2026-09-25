package com.example.starter.domain;

/**
 * 维保登记结果。
 */
public enum MaintenanceResult {
    /** 周期内完成（恰第 15 日亦合规） */
    COMPLIANT,
    /** 逾期完成：锁定电梯并列入重点监察 */
    OVERDUE,
    /** 迟到事件：结案后迟到或乱序补录，仅记录不变更状态 */
    LATE_EVENT
}
