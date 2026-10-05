package com.example.starter.domain;

/**
 * 升温操作票状态机：EXECUTING 升温中 → HOLDING 保温中 → COMPLETED 曲线走完（终态，允许投料）。
 */
public enum WarmUpTicketStatus {
    /** 升温段执行中。 */
    EXECUTING,
    /** 恰满最长用时未达目标，自动转入保温段。 */
    HOLDING,
    /** 曲线全部阶段走完，终态，允许投料。 */
    COMPLETED
}
