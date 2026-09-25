package com.example.starter.domain;

/**
 * 停复梯原因。
 */
public enum StopReason {
    /** 困人救援停梯 */
    RESCUE,
    /** 90 日内 2 次困人强制停梯检查 */
    FORCE_STOP_90D,
    /** 维保逾期锁定 */
    MAINTENANCE_OVERDUE,
    /** 复检合格复梯 */
    RECHECK_PASS
}
