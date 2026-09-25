package com.example.starter.margin.domain;

/**
 * 追加保证金入账结果。
 */
public enum DepositResult {
    /** 首次入账成功 */
    ACCEPTED,
    /** 同 requestId 重放，返回首次结果，未重复入账 */
    REPLAYED,
    /** 强平已开始执行，迟到入账计入余下负债重算 */
    APPLIED_TO_REMAINING,
    /** 账户已全额强平终结，入账不再改变结果 */
    REJECTED_CLOSED
}
