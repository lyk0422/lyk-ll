package com.example.starter.hazmat.domain;

/**
 * 途经点监控状态。
 */
public enum WaypointState {

    /** 未打卡且未超时。 */
    PENDING,
    /** 已超时未打卡（偏离预警）。 */
    TIMED_OUT,
    /** 准点打卡。 */
    CHECKED,
    /** 超时后补打（仍计为已打卡，但保留迟到事实）。 */
    CHECKED_LATE
}
