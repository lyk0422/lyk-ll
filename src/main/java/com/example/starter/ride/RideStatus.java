package com.example.starter.ride;

/**
 * 设备状态：日检→试运行→开放→运行监测→闭园复检 流转状态。
 */
public enum RideStatus {

    /** 当日尚未通过日检。 */
    PENDING_DAILY_CHECK,
    /** 日检合格，试运行进行中。 */
    TRIAL,
    /** 恰 3 次空载试运行合格，等待首次开放。 */
    READY,
    /** 已开放载客（在园运行）。 */
    OPEN,
    /** 运行中载荷或速度严格超阈值，已缓停。 */
    SLOW_STOPPED,
    /** 压杠、安全带或联锁装置失效，整机故障停运。 */
    FAULT_STOPPED,
    /** 停运后复检合格，可复开。 */
    RECHECK_PASSED,
    /** 闭园归档终态。 */
    ARCHIVED
}
