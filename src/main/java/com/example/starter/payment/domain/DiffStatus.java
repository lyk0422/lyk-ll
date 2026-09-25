package com.example.starter.payment.domain;

/**
 * 对账差异处理状态。OPEN：待处理；RESOLVED：已处理（核销/补记/退款），不可重复处理。
 */
public enum DiffStatus {
    OPEN,
    RESOLVED
}
