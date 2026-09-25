package com.example.starter.pension.domain;

/**
 * 期次状态：每期最多出款一次。
 */
public enum InstallmentStatus {
    /** 待支付，失败后仍为本状态，允许重试。 */
    PENDING,
    /** 已出款，终态。 */
    PAID
}
