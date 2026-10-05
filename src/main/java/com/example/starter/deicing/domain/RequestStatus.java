package com.example.starter.deicing.domain;

/**
 * 除冰排队记录状态。
 */
public enum RequestStatus {

    /** 已受理，等待出队。 */
    QUEUED,

    /** 已出队确认，车位与作业窗口已排定。 */
    SCHEDULED,

    /** 除冰作业开始（车位不可再被抢占）。 */
    IN_PROGRESS,

    /** 前序延误导致无法按计划满足起飞/HOT 约束，等待航班重排起飞时刻。 */
    HOT_RISK,

    /** 两步法受理时库存不足，转入人工确认队列（未预占库存，不参与出队）。 */
    MANUAL_REVIEW,

    /** 终态：已起飞。 */
    TAKEN_OFF,

    /** 终态：已取消。 */
    CANCELLED,

    /** 终态：实际起飞时 HOT 已超时，除冰失效，需重新除冰。 */
    HOT_EXPIRED
}
