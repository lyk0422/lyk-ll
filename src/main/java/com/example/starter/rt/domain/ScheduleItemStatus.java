package com.example.starter.rt.domain;

/**
 * 排程项（单分次设备窗口占用）状态。
 */
public enum ScheduleItemStatus {

    /** 已排程：占用设备窗口资源。 */
    SCHEDULED,
    /** 已取消：占用已释放，不构成执行证据。 */
    CANCELLED,
    /** 已完成：受控治疗在窗口内执行，占用释放并冻结实绩。 */
    COMPLETED
}
