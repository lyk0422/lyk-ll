package com.example.starter.water;

/**
 * 处置指令状态：PENDING 待确认；CONFIRMED 回执已确认；ESCALATED 恰满 5 分钟未确认已升级。
 */
public enum DisposalStatus {
    PENDING,
    CONFIRMED,
    ESCALATED
}
