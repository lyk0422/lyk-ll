package com.example.starter.rt.domain;

/**
 * 排程占用项状态。
 */
public enum ScheduleItemStatus {

    /** 已占用未开始：占用设备窗口，可在窗口内由受控入口完成治疗。 */
    SCHEDULED,
    /** 已完成：占用已原子释放，实绩（执行时刻、剂量）已冻结。 */
    COMPLETED,
    /** 已取消：占用已释放（人工取消或计划改版）。 */
    CANCELLED
}
