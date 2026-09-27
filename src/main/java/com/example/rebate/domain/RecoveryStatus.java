package com.example.rebate.domain;

/**
 * 追缴明细状态：{@code RECOVERING} 追缴中（允许部分到账）；{@code COMPLETED} 累计恰收齐。
 */
public enum RecoveryStatus {
    /** 追缴中，已收回金额小于批准金额。 */
    RECOVERING,
    /** 累计实际收回恰等于批准金额，终态。 */
    COMPLETED
}
