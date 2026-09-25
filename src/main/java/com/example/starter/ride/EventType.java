package com.example.starter.ride;

/**
 * 启停链事件类型，可由事件表完整复算。
 */
public enum EventType {

    /** 日检逐项合格。 */
    DAILY_CHECK_PASSED,
    /** 日检存在不合格项。 */
    DAILY_CHECK_FAILED,
    /** 完成一次空载试运行。 */
    TRIAL_COMPLETED,
    /** 首次开放。 */
    OPEN,
    /** 故障/缓停复检后复开。 */
    REOPEN,
    /** 超阈值缓停。 */
    SLOW_STOP,
    /** 安全装置失效整机停运。 */
    FAULT_STOP,
    /** 停运后复检合格。 */
    RECHECK_PASSED,
    /** 闭园归档终态。 */
    ARCHIVE,
    /** 归档后的迟到运行读数。 */
    LATE_READING
}
