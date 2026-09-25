package com.example.starter.margin.domain;

/**
 * 追保通知状态。
 */
public enum NoticeStatus {
    /** 未结清：等待客户在截止时间前追加担保物 */
    OPEN,
    /** 已解除：追加担保物后比例回到 140% 及以上 */
    RESOLVED,
    /** 已逾期：截止时间后仍未补足，转入强制平仓 */
    EXPIRED
}
