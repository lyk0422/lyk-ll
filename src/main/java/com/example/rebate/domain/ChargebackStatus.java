package com.example.rebate.domain;

/**
 * 冲回单状态。
 *
 * <p>PENDING 待复核；APPROVED 已批准（同一事务内占用剩余可冲额并产生独立追缴明细）；
 * REJECTED 已驳回（不占额）。</p>
 */
public enum ChargebackStatus {
    PENDING,
    APPROVED,
    REJECTED
}
