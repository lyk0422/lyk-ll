package com.example.starter.fishery.domain;

/**
 * 跨船配额调剂单状态：PENDING 待对方确认，EFFECTIVE 双方确认已生效，EXPIRED 48 小时未确认已失效。
 */
public enum TransferStatus {
    /** 待对方确认（发起方提交即视为发起方已确认）。 */
    PENDING,
    /** 双方确认，调剂量已在同一事务内完成双方额度变更。 */
    EFFECTIVE,
    /** 确认时限（UTC 48 小时，恰满即失效）内未确认，额度不变，可重新发起。 */
    EXPIRED
}
