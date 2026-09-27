package com.example.starter.model;

/**
 * 粮仓状态：日常监测→异常告警→通风作业→效果复测→归档；两轮复测仍超标转人工处置。
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
    /** 熏蒸作业中：仓锁定，禁止人员进入与普通通风 */
    FUMIGATING,
    /** 熏蒸后散气中：仓仍锁定，风机散气 */
    AERATING,
    /** 已归档（终态），此后迟到温湿度数据只记迟到事件 */
    ARCHIVED
}
