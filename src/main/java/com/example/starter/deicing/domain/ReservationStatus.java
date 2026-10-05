package com.example.starter.deicing.domain;

/**
 * 库存预占状态。
 */
public enum ReservationStatus {

    /** 预占中：两步法受理时按车位占用时长与配比预占，尚未转消耗。 */
    ACTIVE,

    /** 已转消耗：作业完成或起飞判定时按预占量 FEFO 扣减批次。 */
    CONSUMED,

    /** 已释放：作业开始前取消，预占解除，不进入消耗台账。 */
    RELEASED
}
