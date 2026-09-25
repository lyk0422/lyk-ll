package com.example.starter.rebate.domain;

/**
 * 退库核销状态：CONSISTENT 到账一致（差额恰 0.5% 含内）；DISCREPANCY 超 0.5% 转人工。
 */
public enum RefundStatus {
    CONSISTENT,
    DISCREPANCY
}
