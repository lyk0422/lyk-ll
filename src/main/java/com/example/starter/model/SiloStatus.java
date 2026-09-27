package com.example.starter.model;

/**
 * 粮仓状态：日常监测→异常告警→通风作业→效果复测→归档；两轮复测仍超标转人工处置。
 * 熏蒸联锁：熏蒸中/散气中锁仓，禁止人员进入与普通通风，解除后恢复锁仓前状态。
 */
public enum SiloStatus {
    /** 日常监测 */
    MONITORING,
    /** 异常告警 */
    ALARMING,
    /** 通风作业 */
    VENTILATING,
    /** 效果复测 */
    RECHECKING,
    /** 两轮通风仍超标，转人工处置 */
    MANUAL_HANDLING,
    /** 熏蒸作业（锁仓，禁止普通通风） */
    FUMIGATING,
    /** 散气作业（锁仓，禁止普通通风） */
    AERATING,
    /** 已归档（终态），此后迟到温湿度数据只记迟到事件 */
    ARCHIVED
}
