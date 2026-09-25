package com.example.starter.domain;

/**
 * 强平触发原因：DIRECT_BELOW_110 比例低于110%直接强平；OVERDUE 追保逾期未补足。
 */
public enum LiquidationReason {
    DIRECT_BELOW_110,
    OVERDUE
}
