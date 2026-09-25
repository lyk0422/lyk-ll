package com.example.starter.domain;

/**
 * 粮仓流转状态。
 */
public enum SiloStatus {
    /** 日常监测 */
    MONITORING,
    /** 异常告警 */
    ALARM,
    /** 通风作业执行中 */
    VENTILATING,
    /** 通风完成待复测 */
    RETEST_PENDING,
    /** 复测超标（一轮后可二次通风） */
    RETEST_FAIL,
    /** 复测达标 */
    RETEST_PASS,
    /** 两轮复测仍超标，转人工处置 */
    MANUAL,
    /** 已归档（终态） */
    ARCHIVED
}
