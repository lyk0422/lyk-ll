package com.example.starter.fishery.domain;

/**
 * 跨船配额调剂单状态，流转：PENDING（待双方确认）→ EFFECTIVE（双方确认生效）；
 * 确认时限 48 小时（UTC）恰满仍未双方确认则自动失效为 EXPIRED，失效单可重新发起。
 */
public enum TransferStatus {
    /** 待双方确认（进行中）。 */
    PENDING,
    /** 双方已确认，调剂生效且双方额度已同事务变更。 */
    EFFECTIVE,
    /** 确认时限恰满未确认，自动失效，额度不变。 */
    EXPIRED
}
