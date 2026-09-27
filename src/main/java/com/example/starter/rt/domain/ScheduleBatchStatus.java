package com.example.starter.rt.domain;

/**
 * 排程批次状态。
 */
public enum ScheduleBatchStatus {

    /** 存在未开始（SCHEDULED）排程项。 */
    ACTIVE,
    /** 已取消：全部未开始项已释放占用（人工取消或计划改版）。 */
    CANCELLED,
    /** 全部排程项已完成。 */
    COMPLETED
}
