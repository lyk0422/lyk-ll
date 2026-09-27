package com.example.starter.rt.domain;

/**
 * 排程批次状态。
 */
public enum ScheduleStatus {

    /** 生效中：批次内尚有占用中的排程项。 */
    ACTIVE,
    /** 已取消：未开始项已释放占用，已完成项保留实绩。 */
    CANCELLED
}
