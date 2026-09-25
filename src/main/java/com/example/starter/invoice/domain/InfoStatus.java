package com.example.starter.invoice.domain;

/**
 * 红字信息表状态：待确认→已确认（开具红票）；意见不一致挂起。
 */
public enum InfoStatus {
    /** 待双方确认 */
    PENDING,
    /** 双方已确认并开具红票 */
    CONFIRMED,
    /** 意见不一致，已挂起 */
    SUSPENDED
}
