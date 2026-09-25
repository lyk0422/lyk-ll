package com.example.starter.domain;

/**
 * 掺烧方案流转状态：申报→审核→执行→（停止/禁烧）→日结封存。
 */
public enum PlanStatus {

    /** 已申报，待审核 */
    DECLARED,
    /** 审核通过，待下发执行 */
    APPROVED,
    /** 执行中：同一窑口同一时刻仅允许一个此状态方案 */
    EXECUTING,
    /** 已停止（方案切换先停旧） */
    STOPPED,
    /** 审核拒绝：替代率/氯硫/许可校验不通过，整单拒绝的终态 */
    REJECTED,
    /** 当日连续 3 个小时均值超标，强制禁烧 */
    BANNED,
    /** 日结封存，终态 */
    SEALED
}
