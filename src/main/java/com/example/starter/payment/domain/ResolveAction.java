package com.example.starter.payment.domain;

/**
 * 差异处理方式。
 * WRITE_OFF：核销（本地多账）；
 * SUPPLEMENT：补记（渠道多账补登本地）；
 * REFUND：退款（金额差错退款）。
 */
public enum ResolveAction {
    WRITE_OFF,
    SUPPLEMENT,
    REFUND
}
