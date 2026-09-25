package com.example.starter.payment.domain;

/**
 * 交易入账状态。
 * POSTED：已归入某开放批次并计入清算；
 * SUSPENDED：到达时目标批次已封账，挂账待调账。
 */
public enum TxnStatus {
    POSTED,
    SUSPENDED
}
