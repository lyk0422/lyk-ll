package com.example.starter.pension.domain;

/**
 * 支付事由：成员离职或退休方可发起支付申请。
 */
public enum PayoutReason {
    /** 离职支付。 */
    RESIGNED,
    /** 退休支付。 */
    RETIRED
}
