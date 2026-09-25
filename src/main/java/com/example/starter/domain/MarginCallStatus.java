package com.example.starter.domain;

/**
 * 追保通知状态：OPEN 未结清；RESOLVED 已补足结清；LIQUIDATED 逾期已强平。
 */
public enum MarginCallStatus {
    OPEN,
    RESOLVED,
    LIQUIDATED
}
