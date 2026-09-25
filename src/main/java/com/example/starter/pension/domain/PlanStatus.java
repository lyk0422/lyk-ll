package com.example.starter.pension.domain;

/**
 * 支付计划状态。结清为终态。
 */
public enum PlanStatus {
    /** 进行中：存在未完成期次，同一成员同时刻只允许一个。 */
    ACTIVE,
    /** 已结清：终态，全部期次完成后自动进入，不可再改动。 */
    SETTLED
}
