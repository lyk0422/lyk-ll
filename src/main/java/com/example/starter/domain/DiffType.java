package com.example.starter.domain;

/** 对账差异类型：LOCAL_EXTRA 本地多账；CHANNEL_EXTRA 渠道多账；AMOUNT_MISMATCH 金额差错。 */
public enum DiffType {
    LOCAL_EXTRA,
    CHANNEL_EXTRA,
    AMOUNT_MISMATCH
}
