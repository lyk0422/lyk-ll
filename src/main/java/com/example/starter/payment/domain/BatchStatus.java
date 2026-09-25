package com.example.starter.payment.domain;

/**
 * 批次状态。OPEN：开放入账；CLOSED：已封账（终态），封账后清算单不可重算。
 */
public enum BatchStatus {
    OPEN,
    CLOSED
}
