package com.example.starter.payment.domain;

/**
 * 对账差异类型。
 * LOCAL_EXTRA：本地有账、渠道无账（本地多账）；
 * CHANNEL_EXTRA：渠道有账、本地无账（渠道多账）；
 * AMOUNT_MISMATCH：双方都有但金额不一致（差错）。
 */
public enum DiffType {
    LOCAL_EXTRA,
    CHANNEL_EXTRA,
    AMOUNT_MISMATCH
}
