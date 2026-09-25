package com.example.starter.domain;

/**
 * 批次状态。
 */
public enum LotStatus {
    /** 待定：双检未齐或尚有不合格项 */
    PENDING,
    /** 已分级发证 */
    CERTIFIED,
    /** 注销（终态） */
    CANCELLED
}
